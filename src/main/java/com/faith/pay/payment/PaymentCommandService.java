package com.faith.pay.payment;

import com.faith.pay.error.*;
import com.faith.pay.order.*;
import com.faith.pay.payment.audit.*;
import com.faith.pay.provider.core.ProviderPaymentResult;
import com.faith.pay.provider.dto.PaystackInitializeData;
import com.faith.pay.provider.core.PaymentProviderType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentCommandService {

    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final PaymentEventStore events;

    public PaymentCommandService(
            PaymentRepository payments,
            OrderRepository orders,
            PaymentEventStore events
    ) {
        this.payments = payments;
        this.orders = orders;
        this.events = events;
    }

    @Transactional
    public PaymentReservation reserve(Long orderId, String idempotencyKey) {
        return reserve(orderId, idempotencyKey, PaymentProviderType.PAYSTACK);
    }

    @Transactional
    public PaymentReservation reserve(Long orderId, String idempotencyKey, PaymentProviderType provider) {
        var existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (!payment.getOrder().getId().equals(orderId) || payment.getProvider() != provider) {
                throw new IdempotencyKeyReuseException();
            }
            return reservation(payment, false);
        }

        Order order = orders.findByIdForUpdate(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));

        // Recheck after locking the order to serialize competing reservations.
        existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (!payment.getOrder().getId().equals(orderId) || payment.getProvider() != provider) {
                throw new IdempotencyKeyReuseException();
            }
            return reservation(payment, false);
        }

        if (order.getStatus() == OrderStatus.PAID) {
            throw new OrderAlreadyPaidException(order.getId());
        }

        var open = payments.findOpenPaymentForOrder(order.getId());
        if (open.isPresent()) {
            if (open.get().getStatus() == PaymentStatus.PENDING) {
                throw new PaymentInitializationInProgressException(order.getId());
            }
            throw new PaymentAlreadyInProgressException(order.getId());
        }

        Payment payment = new Payment(newReference(), order, order.getTotalKobo(), idempotencyKey, provider);
        payments.saveAndFlush(payment);
        events.append(payment, PaymentEventType.PAYMENT_RESERVED, null,
                PaymentStatus.PENDING, PaymentEventSource.API, null);
        return reservation(payment, true);
    }

    @Transactional
    public PaymentReservation markInitialized(String reference, ProviderPaymentResult provider) {
        Payment payment = payments.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return reservation(payment, false);
        }
        if (provider.provider() != payment.getProvider()
                || !payment.getReference().equals(provider.reference())) {
            throw new AppException(ErrorCode.PROVIDER_RESPONSE_INVALID,
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Initialization result does not match the reserved payment provider and reference");
        }

        payment.markProcessing(provider.checkoutUrl(), provider.providerSessionId());
        events.append(payment, payment.getProvider() == PaymentProviderType.PAYSTACK
                        ? PaymentEventType.PAYSTACK_INITIALIZED : PaymentEventType.PROVIDER_INITIALIZED,
                PaymentStatus.PENDING, PaymentStatus.PROCESSING,
                payment.getProvider() == PaymentProviderType.PAYSTACK
                        ? PaymentEventSource.PAYSTACK_INITIALIZE : PaymentEventSource.PROVIDER_INITIALIZE, null);
        return reservation(payment, false);
    }

    /** Legacy Paystack boundary retained for callers not yet migrated. */
    @Transactional
    public PaymentReservation markInitialized(String reference, PaystackInitializeData provider) {
        return markInitialized(reference, new ProviderPaymentResult(PaymentProviderType.PAYSTACK, null,
                provider.reference(), com.faith.pay.provider.core.ProviderPaymentState.REQUIRES_ACTION,
                provider.authorizationUrl(), null, provider.accessCode()));
    }

    @Transactional
    public void markInitializationUncertain(String reference) {
        Payment payment = payments.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        if (payment.getStatus() == PaymentStatus.PENDING) {
            boolean paystack = payment.getProvider() == PaymentProviderType.PAYSTACK;
            events.append(payment, paystack ? PaymentEventType.PAYSTACK_INITIALIZATION_UNCERTAIN
                            : PaymentEventType.PROVIDER_INITIALIZATION_UNCERTAIN,
                    PaymentStatus.PENDING, PaymentStatus.PENDING,
                    paystack ? PaymentEventSource.PAYSTACK_INITIALIZE : PaymentEventSource.PROVIDER_INITIALIZE, null);
        }
    }

    private PaymentReservation reservation(Payment payment, boolean newlyCreated) {
        return new PaymentReservation(payment.getId(), payment.getOrder().getId(),
                payment.getOrder().getCustomerEmail(), payment.getAmountKobo(),
                payment.getCurrency(), payment.getReference(), payment.getProvider(), payment.getStatus(),
                payment.getAuthorizationUrl(), newlyCreated);
    }

    private String newReference() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "");
    }
}
