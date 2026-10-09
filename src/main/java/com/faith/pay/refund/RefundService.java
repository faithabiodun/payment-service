package com.faith.pay.refund;

import com.faith.pay.payment.Payment;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.payment.PaymentStatus;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackRefundData;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RefundService {

    private final RefundRepository refundRepository;
    private final PaymentRepository paymentRepository;
    private final PaystackClient paystackClient;

    public RefundService(
            RefundRepository refundRepository,
            PaymentRepository paymentRepository,
            PaystackClient paystackClient
    ) {
        this.refundRepository = refundRepository;
        this.paymentRepository = paymentRepository;
        this.paystackClient = paystackClient;
    }

    @Transactional
    public Refund refund(
            String paymentReference,
            long amountKobo,
            String reason,
            String idempotencyKey
    ) {
        if (idempotencyKey == null
                || idempotencyKey.isBlank()) {

            throw new IllegalArgumentException(
                    "Idempotency-Key is required"
            );
        }

        if (paymentReference == null
                || paymentReference.isBlank()) {

            throw new IllegalArgumentException(
                    "Payment reference is required"
            );
        }

        if (amountKobo <= 0) {
            throw new IllegalArgumentException(
                    "Refund amount must be positive"
            );
        }

        Refund existing = refundRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElse(null);

        if (existing != null) {
            validateReplay(
                    existing,
                    paymentReference,
                    amountKobo,
                    reason
            );

            return existing;
        }

        Payment payment = paymentRepository
                .findByReferenceForUpdate(paymentReference)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Payment not found"
                ));

        // Recheck after taking the payment lock to serialize concurrent refunds.
        existing = refundRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElse(null);

        if (existing != null) {
            validateReplay(
                    existing,
                    paymentReference,
                    amountKobo,
                    reason
            );

            return existing;
        }

        if (payment.getStatus() != PaymentStatus.SUCCESS
                && payment.getStatus() != PaymentStatus.PARTIALLY_REFUNDED) {

            throw new IllegalStateException(
                    "Only successful payments can be refunded"
            );
        }

        long reserved = refundRepository
                .totalReservedRefundAmount(payment.getId());

        if (amountKobo > payment.getAmountKobo() - reserved) {
            throw new IllegalArgumentException(
                    "Refund amount exceeds the remaining refundable amount"
            );
        }

        Refund refund = new Refund(
                payment,
                newReference(),
                amountKobo,
                reason,
                idempotencyKey
        );

        refundRepository.saveAndFlush(refund);

        PaystackRefundData providerRefund = paystackClient.refund(
                payment.getReference(),
                amountKobo,
                reason
        );

        String providerStatus = providerRefund.status();

        if (providerStatus == null
                || providerStatus.isBlank()) {

            throw new IllegalStateException(
                    "Paystack returned a refund without a status"
            );
        }

        refund.markProcessing(
                providerRefund.id().toString()
        );

        if ("processed".equalsIgnoreCase(providerStatus)
                || "success".equalsIgnoreCase(providerStatus)) {

            refund.markSuccess();
            payment.addRefundedKobo(amountKobo);

        } else if ("failed".equalsIgnoreCase(providerStatus)) {
            refund.markFailed();
        }

        return refund;
    }

    private void validateReplay(
            Refund refund,
            String paymentReference,
            long amountKobo,
            String reason
    ) {
        if (!refund.getPayment().getReference().equals(paymentReference)
                || refund.getAmountKobo() != amountKobo
                || !java.util.Objects.equals(refund.getReason(), reason)) {

            throw new IllegalArgumentException(
                    "Idempotency key was used for another request"
            );
        }
    }

    private String newReference() {
        return "REF-" + UUID.randomUUID()
                .toString()
                .replace("-", "");
    }
}
