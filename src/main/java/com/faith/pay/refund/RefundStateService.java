package com.faith.pay.refund;

import com.faith.pay.error.*;
import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.*;
import com.faith.pay.provider.dto.PaystackRefundData;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class RefundStateService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentEventStore events;

    public RefundStateService(
            RefundRepository refunds,
            PaymentRepository payments,
            PaymentEventStore events
    ) {
        this.refunds = refunds;
        this.payments = payments;
        this.events = events;
    }

    @Transactional
    public Refund applyProviderState(
            String refundReference,
            PaystackRefundData provider,
            PaymentEventSource source,
            String rawPayload
    ) {
        Refund initial = refunds.findByReference(refundReference)
                .orElseThrow(() -> refundNotFound(refundReference));

        // Every refund state change locks payment first, then refund.
        Payment payment = payments.findByReferenceForUpdate(initial.getPayment().getReference())
                .orElseThrow(() -> new PaymentNotFoundException(initial.getPayment().getReference()));
        Refund refund = refunds.findByReferenceForUpdate(refundReference)
                .orElseThrow(() -> refundNotFound(refundReference));

        validateProviderData(refund, payment, provider, source);

        RefundStatus target = mapStatus(provider.status());
        RefundStatus current = refund.getStatus();

        if (current.isTerminal()) {
            if (current == target) {
                return refund;
            }
            throw invalidTransition(current, target);
        }

        if (current == RefundStatus.NEEDS_ATTENTION
                && target == RefundStatus.PROCESSING
                && "pending".equalsIgnoreCase(provider.status())) {
            // Ignore an older pending notification after Paystack requested more details.
            return refund;
        }

        if (!canTransition(current, target)) {
            throw invalidTransition(current, target);
        }

        if (current == target
                && provider.status().equalsIgnoreCase(refund.getProviderStatus())
                && (provider.id() == null
                    || provider.id().toString().equals(refund.getProviderRefundId()))) {
            return refund;
        }

        PaymentStatus paymentStatus = payment.getStatus();
        boolean submitted = current == RefundStatus.PENDING;
        boolean statusChanged = current != target;
        String providerRefundId = provider.id() == null
                ? null
                : provider.id().toString();

        switch (target) {
            case PROCESSING -> refund.markProcessing(providerRefundId, provider.status());
            case NEEDS_ATTENTION -> refund.markNeedsAttention(providerRefundId, provider.status());
            case FAILED -> refund.markFailed(providerRefundId, provider.status());
            case SUCCESS -> {
                refund.markSuccessful(providerRefundId, provider.status());
                payment.addRefundedKobo(refund.getAmountKobo());
            }
            case PENDING -> throw invalidTransition(current, target);
        }

        if (submitted) {
            events.append(payment, PaymentEventType.REFUND_SUBMITTED,
                    paymentStatus, paymentStatus, source, rawPayload);
        }

        if (statusChanged) {
            PaymentEventType event = eventFor(target);
            events.append(payment, event,
                    paymentStatus, payment.getStatus(), source, rawPayload);
        }

        return refund;
    }

    private void validateProviderData(
            Refund refund,
            Payment payment,
            PaystackRefundData provider,
            PaymentEventSource source
    ) {
        if (provider == null || provider.status() == null || provider.status().isBlank()) {
            throw invalidProviderResponse("Paystack refund did not include a status");
        }
        if (provider.amount() != refund.getAmountKobo()) {
            throw invalidProviderResponse("Paystack refund amount did not match the reserved amount");
        }
        if (provider.currency() == null
                || !payment.getCurrency().equalsIgnoreCase(provider.currency())) {
            throw invalidProviderResponse("Paystack refund currency did not match the payment currency");
        }
        if (source == PaymentEventSource.REFUND && provider.id() == null) {
            throw invalidProviderResponse("Paystack refund response did not include an id");
        }

        String providerRefundId = provider.id() == null ? null : provider.id().toString();
        if (providerRefundId != null) {
            if (refund.getProviderRefundId() != null
                    && !refund.getProviderRefundId().equals(providerRefundId)) {
                throw invalidProviderResponse("Paystack returned a different id for this refund");
            }

            refunds.findByProviderRefundId(providerRefundId)
                    .filter(other -> !other.getReference().equals(refund.getReference()))
                    .ifPresent(other -> {
                        throw invalidProviderResponse("Paystack refund id is already linked to another refund");
                    });
        }

        if (!payment.getStatus().hasSuccessfulCharge()) {
            throw new RefundNotAllowedException("Only successful payments can have refund state applied");
        }
    }

    private RefundStatus mapStatus(String providerStatus) {
        return switch (providerStatus.toLowerCase(Locale.ROOT)) {
            case "pending", "processing" -> RefundStatus.PROCESSING;
            case "needs-attention", "needs_attention" -> RefundStatus.NEEDS_ATTENTION;
            case "failed" -> RefundStatus.FAILED;
            case "processed" -> RefundStatus.SUCCESS;
            default -> throw invalidProviderResponse("Unsupported Paystack refund status: " + providerStatus);
        };
    }

    private boolean canTransition(RefundStatus current, RefundStatus target) {
        return switch (current) {
            case PENDING -> true;
            case PROCESSING -> target == RefundStatus.PROCESSING
                    || target == RefundStatus.NEEDS_ATTENTION
                    || target == RefundStatus.FAILED
                    || target == RefundStatus.SUCCESS;
            case NEEDS_ATTENTION -> target == RefundStatus.NEEDS_ATTENTION
                    || target == RefundStatus.PROCESSING
                    || target == RefundStatus.FAILED
                    || target == RefundStatus.SUCCESS;
            case SUCCESS, FAILED -> current == target;
        };
    }

    private PaymentEventType eventFor(RefundStatus status) {
        return switch (status) {
            case PROCESSING -> PaymentEventType.REFUND_PROCESSING;
            case NEEDS_ATTENTION -> PaymentEventType.REFUND_NEEDS_ATTENTION;
            case FAILED -> PaymentEventType.REFUND_FAILED;
            case SUCCESS -> PaymentEventType.REFUND_SUCCEEDED;
            case PENDING -> throw new IllegalArgumentException("Pending is not a provider transition");
        };
    }

    private AppException refundNotFound(String reference) {
        return new AppException(ErrorCode.REFUND_NOT_FOUND, HttpStatus.NOT_FOUND,
                "Refund not found: " + reference);
    }

    private AppException invalidProviderResponse(String message) {
        return new AppException(ErrorCode.PROVIDER_RESPONSE_INVALID,
                HttpStatus.BAD_GATEWAY, message);
    }

    private AppException invalidTransition(RefundStatus current, RefundStatus target) {
        return new AppException(ErrorCode.INVALID_PAYMENT_STATE, HttpStatus.CONFLICT,
                "Refund cannot transition from " + current + " to " + target);
    }
}
