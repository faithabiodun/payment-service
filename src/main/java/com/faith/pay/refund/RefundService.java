package com.faith.pay.refund;

import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.pay.refund.dto.RefundRequest;
import com.faith.pay.refund.dto.RefundResponse;
import org.springframework.stereotype.Service;

@Service
public class RefundService {

    private final RefundCommandService commands;
    private final RefundStateService refundStates;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final PaystackClient paystack;

    public RefundService(
            RefundCommandService commands,
            RefundStateService refundStates,
            RefundRepository refunds,
            PaymentRepository payments,
            PaystackClient paystack
    ) {
        this.commands = commands;
        this.refundStates = refundStates;
        this.refunds = refunds;
        this.payments = payments;
        this.paystack = paystack;
    }

    /** Reserves locally, then calls Paystack after the reservation transaction commits. */
    public RefundResponse refund(String paymentReference, RefundRequest request, String idempotencyKey) {
        RefundReservation reservation = commands.reserve(paymentReference, request, idempotencyKey);

        if (!reservation.newlyCreated()) {
            return get(reservation.refundReference());
        }

        Payment payment = payments.findByReference(paymentReference)
                .orElseThrow();

        try {
            PaystackRefundData provider = paystack.createRefund(
                    payment.getReference(),
                    reservation.amountKobo(),
                    reservation.refundReference(),
                    request.reason()
            );
            return RefundResponse.from(refundStates.applyProviderState(
                    reservation.refundReference(),
                    provider,
                    PaymentEventSource.REFUND,
                    null
            ));
        } catch (ProviderCommunicationException failure) {
            // Keep the committed reservation so reconciliation can recover an uncertain result.
            throw failure;
        }
    }

    private RefundResponse get(String reference) {
        return refunds.findByReference(reference)
                .map(RefundResponse::from)
                .orElseThrow();
    }
}
