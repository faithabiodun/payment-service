package com.faith.pay.refund;

import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.*;
import com.faith.pay.refund.dto.RefundRequest;
import com.faith.pay.refund.dto.RefundResponse;
import org.springframework.stereotype.Service;

@Service
public class RefundService {

    private final RefundCommandService commands;
    private final RefundStateService refundStates;
    private final RefundRepository refunds;
    private final PaymentRepository payments;
    private final RefundProviderRegistry providers;

    public RefundService(
            RefundCommandService commands,
            RefundStateService refundStates,
            RefundRepository refunds,
            PaymentRepository payments,
            RefundProviderRegistry providers
    ) {
        this.commands = commands;
        this.refundStates = refundStates;
        this.refunds = refunds;
        this.payments = payments;
        this.providers = providers;
    }

    /** Reserves locally, then calls the selected provider after the reservation transaction commits. */
    public RefundResponse refund(String paymentReference, RefundRequest request, String idempotencyKey) {
        Payment payment = payments.findByReference(paymentReference)
                .orElseThrow();
        RefundProvider provider = providers.get(payment.getProvider());
        provider.validateConfiguration();
        RefundReservation reservation = commands.reserve(paymentReference, request, idempotencyKey);

        if (!reservation.newlyCreated()) {
            return get(reservation.refundReference());
        }

        try {
            ProviderRefundResult result = provider.refund(new ProviderRefundRequest(
                    payment.getReference(), payment.getProviderTransactionId(), reservation.refundReference(),
                    reservation.amountKobo(), payment.getCurrency(), request.reason(), idempotencyKey));
            VerifiedRefund normalized = new VerifiedRefund(result.provider(), result.providerRefundId(),
                    result.refundReference(), result.state(), result.amountMinor(), result.currency(), result.providerStatus());
            return RefundResponse.from(refundStates.applyVerifiedState(
                    reservation.refundReference(),
                    normalized,
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
