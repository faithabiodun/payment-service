package com.faith.pay.refund;

import com.faith.pay.error.RefundAmountExceedsRemainingException;
import com.faith.pay.error.RefundNotAllowedException;
import com.faith.pay.error.IdempotencyKeyReuseException;
import com.faith.pay.order.Order;
import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentStatus;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.payment.audit.PaymentEventStore;
import com.faith.pay.refund.dto.RefundRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RefundCommandServiceTests {

    private RefundRepository refunds;
    private PaymentRepository payments;
    private RefundCommandService commands;

    @BeforeEach
    void setUp() {
        refunds = mock(RefundRepository.class);
        payments = mock(PaymentRepository.class);
        commands = new RefundCommandService(
                refunds,
                payments,
                mock(PaymentEventStore.class)
        );
    }

    @Test
    void onlySuccessfulAndPartiallyRefundedPaymentsCanReserveRefunds() throws Exception {
        PaymentStatus[] statuses = {
                PaymentStatus.PENDING,
                PaymentStatus.PROCESSING,
                PaymentStatus.FAILED,
                PaymentStatus.REFUNDED,
                PaymentStatus.SUCCESS,
                PaymentStatus.PARTIALLY_REFUNDED
        };
        Payment[] paymentStates = new Payment[statuses.length];
        for (int index = 0; index < statuses.length; index++) {
            paymentStates[index] = payment(statuses[index]);
        }
        org.mockito.stubbing.OngoingStubbing<Optional<Payment>> stub =
                when(payments.findByReferenceForUpdate("PAY-1"));
        for (Payment paymentState : paymentStates) {
            stub = stub.thenReturn(Optional.of(paymentState));
        }
        when(refunds.sumReservedAmount(null)).thenReturn(0L);

        for (int index = 0; index < 4; index++) {
            PaymentStatus status = statuses[index];
            assertThrows(RefundNotAllowedException.class,
                    () -> commands.reserve("PAY-1", new RefundRequest(100L, null), "key-" + status));
        }

        for (int index = 4; index < statuses.length; index++) {
            PaymentStatus status = statuses[index];
            RefundReservation reservation = commands.reserve(
                    "PAY-1", new RefundRequest(100L, null), "allowed-" + status);

            assertTrue(reservation.newlyCreated());
            assertEquals(100L, reservation.amountKobo());
        }
    }

    @Test
    void omittedAmountReservesOnlyTheUnreservedRemainder() throws Exception {
        Payment payment = payment(PaymentStatus.SUCCESS);
        when(payments.findByReferenceForUpdate("PAY-1")).thenReturn(Optional.of(payment));
        when(refunds.sumReservedAmount(null)).thenReturn(600_000L);

        RefundReservation reservation = commands.reserve(
                "PAY-1", new RefundRequest(null, "Remaining balance"), "full-key");

        assertEquals(400_000L, reservation.amountKobo());
        Refund saved = captureSavedRefund();
        assertNull(saved.getRequestedAmountKobo());
    }

    @Test
    void requestAboveReservedCapacityIsRejected() throws Exception {
        Payment payment = payment(PaymentStatus.SUCCESS);
        when(payments.findByReferenceForUpdate("PAY-1")).thenReturn(Optional.of(payment));
        when(refunds.sumReservedAmount(null)).thenReturn(600_000L);

        assertThrows(RefundAmountExceedsRemainingException.class,
                () -> commands.reserve("PAY-1", new RefundRequest(600_000L, null), "too-much"));
        verify(refunds, never()).saveAndFlush(any());
    }

    @Test
    void idempotencyReplayMustMatchTheOriginalAmountAndReason() throws Exception {
        Payment payment = payment(PaymentStatus.SUCCESS);
        Refund original = new Refund(payment, "REF-original", 200_000L,
                200_000L, "Same request", "same-key");
        when(refunds.findByIdempotencyKey("same-key")).thenReturn(Optional.of(original));

        RefundReservation replay = commands.reserve(
                "PAY-1", new RefundRequest(200_000L, "Same request"), "same-key");

        assertFalse(replay.newlyCreated());
        assertEquals("REF-original", replay.refundReference());
        assertThrows(IdempotencyKeyReuseException.class,
                () -> commands.reserve("PAY-1", new RefundRequest(null, "Same request"), "same-key"));
        assertThrows(IdempotencyKeyReuseException.class,
                () -> commands.reserve("PAY-1", new RefundRequest(100_000L, "Same request"), "same-key"));
        assertThrows(IdempotencyKeyReuseException.class,
                () -> commands.reserve("PAY-1", new RefundRequest(200_000L, "Different"), "same-key"));
    }

    private Refund captureSavedRefund() {
        var captor = org.mockito.ArgumentCaptor.forClass(Refund.class);
        verify(refunds).saveAndFlush(captor.capture());
        return captor.getValue();
    }

    private Payment payment(PaymentStatus status) throws Exception {
        Payment payment = new Payment("PAY-1", new Order("customer@example.test", 1_000_000L),
                1_000_000L, "payment-key");
        payment.markSuccessful("123", "card", 0L, Instant.now());
        setStatus(payment, status);
        return payment;
    }

    private void setStatus(Payment payment, PaymentStatus status) throws Exception {
        Field field = Payment.class.getDeclaredField("status");
        field.setAccessible(true);
        field.set(payment, status);
    }
}
