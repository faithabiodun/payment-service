package com.faith.pay.payment;

import com.faith.pay.error.*;
import com.faith.pay.order.*;
import com.faith.pay.payment.audit.*;
import com.faith.pay.provider.dto.PaystackInitializeData;
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
        var existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            Payment payment = existing.get();
            if (!payment.getOrder().getId().equals(orderId)) {
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
            if (!payment.getOrder().getId().equals(orderId)) {
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

        Payment payment = new Payment(newReference(), order, order.getTotalKobo(), idempotencyKey);
        payments.saveAndFlush(payment);
        events.append(payment, PaymentEventType.PAYMENT_RESERVED, null,
                PaymentStatus.PENDING, PaymentEventSource.API, null);
        return reservation(payment, true);
    }

    @Transactional
    public PaymentReservation markInitialized(String reference, PaystackInitializeData provider) {
        Payment payment = payments.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        if (payment.getStatus() != PaymentStatus.PENDING) {
            return reservation(payment, false);
        }

        payment.markProcessing(provider.authorizationUrl(), provider.accessCode());
        events.append(payment, PaymentEventType.PAYSTACK_INITIALIZED,
                PaymentStatus.PENDING, PaymentStatus.PROCESSING,
                PaymentEventSource.PAYSTACK_INITIALIZE, null);
        return reservation(payment, false);
    }

    @Transactional
    public void markInitializationUncertain(String reference) {
        Payment payment = payments.findByReferenceForUpdate(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        if (payment.getStatus() == PaymentStatus.PENDING) {
            events.append(payment, PaymentEventType.PAYSTACK_INITIALIZATION_UNCERTAIN,
                    PaymentStatus.PENDING, PaymentStatus.PENDING,
                    PaymentEventSource.PAYSTACK_INITIALIZE, null);
        }
    }

    private PaymentReservation reservation(Payment payment, boolean newlyCreated) {
        return new PaymentReservation(payment.getId(), payment.getOrder().getId(),
                payment.getOrder().getCustomerEmail(), payment.getAmountKobo(),
                payment.getCurrency(), payment.getReference(), payment.getStatus(),
                payment.getAuthorizationUrl(), newlyCreated);
    }

    private String newReference() {
        return "PAY-" + UUID.randomUUID().toString().replace("-", "");
    }
}
