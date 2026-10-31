package com.faith.pay.refund;

import com.faith.pay.error.*;
import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.*;
import com.faith.pay.provider.core.ProviderRefundState;
import com.faith.pay.provider.core.VerifiedRefund;
import com.faith.pay.provider.paystack.PaystackPaymentProvider;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.pay.outbox.OutboxEventStore;
import com.faith.pay.ledger.LedgerPostingService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;

@Service
public class RefundStateService {

    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaymentEventStore events;
    private final OutboxEventStore outbox;
    private final LedgerPostingService ledger;

    public RefundStateService(
            RefundRepository refunds,
            PaymentRepository payments,
            PaymentEventStore events,
            OutboxEventStore outbox,
            LedgerPostingService ledger
    ) {
        this.refunds = refunds;
        this.payments = payments;
        this.events = events;
        this.outbox = outbox;
        this.ledger = ledger;
    }

    @Transactional
    public Refund applyVerifiedState(
            String refundReference,
            VerifiedRefund provider,
            PaymentEventSource source,
            String rawPayload
    ) {
        String paymentReference = refunds.findPaymentReferenceByReference(refundReference)
                .orElseThrow(() -> refundNotFound(refundReference));

        // Every refund state change locks payment first, then refund.
        Payment payment = payments.findByReferenceForUpdate(paymentReference)
                .orElseThrow(() -> new PaymentNotFoundException(paymentReference));
        Refund refund = refunds.findByReferenceForUpdate(refundReference)
                .orElseThrow(() -> refundNotFound(refundReference));

        validateProviderData(refund, payment, provider, source);

        RefundStatus target = mapStatus(provider.state());
        RefundStatus current = refund.getStatus();

        if (current.isTerminal()) {
            if (current == target) {
                return refund;
            }
            throw invalidTransition(current, target);
        }

        if (current == RefundStatus.NEEDS_ATTENTION
                && target == RefundStatus.PROCESSING
                && "pending".equalsIgnoreCase(provider.providerStatus())) {
            // Ignore an older pending notification after Paystack requested more details.
            return refund;
        }

        if (!canTransition(current, target)) {
            throw invalidTransition(current, target);
        }

        if (current == target
                && provider.providerStatus().equalsIgnoreCase(refund.getProviderStatus())
                && (provider.providerRefundId() == null
                    || provider.providerRefundId().equals(refund.getProviderRefundId()))) {
            return refund;
        }

        PaymentStatus paymentStatus = payment.getStatus();
        boolean submitted = current == RefundStatus.PENDING;
        boolean statusChanged = current != target;
        String providerRefundId = provider.providerRefundId();

        switch (target) {
            case PROCESSING -> refund.markProcessing(providerRefundId, provider.providerStatus());
            case NEEDS_ATTENTION -> refund.markNeedsAttention(providerRefundId, provider.providerStatus());
            case FAILED -> refund.markFailed(providerRefundId, provider.providerStatus());
            case SUCCESS -> {
                refund.markSuccessful(providerRefundId, provider.providerStatus());
                payment.addRefundedKobo(refund.getAmountKobo());
                ledger.postRefundSuccess(refund);
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
            outbox.append("REFUND", refund.getReference(), event.name(),
                    java.util.Map.of("refundReference", refund.getReference(),
                            "paymentReference", payment.getReference(),
                            "provider", payment.getProvider().name(),
                            "amountMinor", refund.getAmountKobo(),
                            "currency", payment.getCurrency(),
                            "status", target.name()));
        }

        return refund;
    }

    private void validateProviderData(
            Refund refund,
            Payment payment,
            VerifiedRefund provider,
            PaymentEventSource source
    ) {
        if (provider == null || provider.state() == null || provider.state() == ProviderRefundState.UNKNOWN
                || provider.providerStatus() == null || provider.providerStatus().isBlank()) {
            throw invalidProviderResponse("Provider refund did not include a recognized status");
        }
        if (provider.provider() != payment.getProvider()) {
            throw invalidProviderResponse("Refund result provider does not own this payment");
        }
        if (provider.amountMinor() != refund.getAmountKobo()) {
            throw invalidProviderResponse("Provider refund amount did not match the reserved amount");
        }
        if (provider.currency() == null
                || !payment.getCurrency().equalsIgnoreCase(provider.currency())) {
            throw invalidProviderResponse("Provider refund currency did not match the payment currency");
        }
        if (source == PaymentEventSource.REFUND && provider.providerRefundId() == null) {
            throw invalidProviderResponse("Provider refund response did not include an id");
        }

        String providerRefundId = provider.providerRefundId();
        if (providerRefundId != null) {
            if (refund.getProviderRefundId() != null
                    && !refund.getProviderRefundId().equals(providerRefundId)) {
                throw invalidProviderResponse("Provider returned a different id for this refund");
            }

            refunds.findByProviderAndProviderRefundId(provider.provider(), providerRefundId)
                    .filter(other -> !other.getReference().equals(refund.getReference()))
                    .ifPresent(other -> {
                        throw invalidProviderResponse("Provider refund id is already linked to another refund");
                    });
        }

        if (!payment.getStatus().hasSuccessfulCharge()) {
            throw new RefundNotAllowedException("Only successful payments can have refund state applied");
        }
    }

    private RefundStatus mapStatus(ProviderRefundState providerState) {
        return switch (providerState) {
            case PENDING, PROCESSING -> RefundStatus.PROCESSING;
            case NEEDS_ATTENTION -> RefundStatus.NEEDS_ATTENTION;
            case FAILED -> RefundStatus.FAILED;
            case SUCCEEDED -> RefundStatus.SUCCESS;
            case UNKNOWN -> throw invalidProviderResponse("Unsupported provider refund status");
        };
    }

    /** Temporary Paystack edge retained while webhook and reconciliation callers migrate. */
    @Transactional
    public Refund applyProviderState(String refundReference, PaystackRefundData provider,
                                     PaymentEventSource source, String rawPayload) {
        return applyVerifiedState(refundReference,
                PaystackPaymentProvider.toVerifiedRefund(provider, refundReference), source, rawPayload);
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
