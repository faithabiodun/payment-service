package com.faith.pay.provider.paystack;

import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.core.*;
import com.faith.pay.provider.dto.PaystackInitializeData;
import com.faith.pay.provider.dto.PaystackTransactionData;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Translates Paystack's API vocabulary into the application's provider-neutral port. */
@Component
public class PaystackPaymentProvider implements PaymentProvider, RefundProvider {
    private final PaystackClient client;

    public PaystackPaymentProvider(PaystackClient client) { this.client = client; }

    @Override public PaymentProviderType type() { return PaymentProviderType.PAYSTACK; }

    @Override
    public ProviderPaymentResult initialize(ProviderPaymentRequest request) {
        PaystackInitializeData data = client.initialize(request.customerEmail(), request.amountMinor(), request.reference());
        return new ProviderPaymentResult(type(), null, request.reference(), ProviderPaymentState.REQUIRES_ACTION,
                data.authorizationUrl(), null);
    }

    @Override
    public VerifiedPayment verify(String reference, String providerPaymentId) {
        return payment(client.verify(reference));
    }

    public VerifiedPayment payment(PaystackTransactionData data) {
        return new VerifiedPayment(type(), data.id() == null ? null : data.id().toString(), data.reference(),
                paymentState(data.status()), data.amount(), data.currency(), data.channel(), data.fees(), data.paidAt());
    }

    @Override
    public ProviderRefundResult refund(ProviderRefundRequest request) {
        var data = client.createRefund(request.paymentReference(), request.amountMinor(), request.refundReference(), request.reason());
        return new ProviderRefundResult(type(), data.id() == null ? null : data.id().toString(), request.refundReference(),
                refundState(data.status()), data.amount(), data.currency(), data.status());
    }

    @Override
    public VerifiedRefund verifyRefund(String providerRefundId) {
        var data = client.fetchRefund(providerRefundId);
        return new VerifiedRefund(type(), data.id() == null ? null : data.id().toString(), null,
                refundState(data.status()), data.amount(), data.currency(), data.status());
    }

    public VerifiedRefund refundState(com.faith.pay.provider.dto.PaystackRefundData data, String localReference) {
        return new VerifiedRefund(type(), data.id() == null ? null : data.id().toString(), localReference,
                refundState(data.status()), data.amount(), data.currency(), data.status());
    }

    public static ProviderPaymentState paymentState(String status) {
        if (status == null) return ProviderPaymentState.UNKNOWN;
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "success" -> ProviderPaymentState.SUCCEEDED;
            case "pending", "ongoing", "queued" -> ProviderPaymentState.PENDING;
            case "processing" -> ProviderPaymentState.PROCESSING;
            case "failed" -> ProviderPaymentState.FAILED;
            case "abandoned" -> ProviderPaymentState.CANCELLED;
            default -> ProviderPaymentState.UNKNOWN;
        };
    }

    public static ProviderRefundState refundState(String status) {
        if (status == null) return ProviderRefundState.UNKNOWN;
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "pending" -> ProviderRefundState.PENDING;
            case "processing", "ongoing" -> ProviderRefundState.PROCESSING;
            case "processed", "success", "succeeded" -> ProviderRefundState.SUCCEEDED;
            case "failed" -> ProviderRefundState.FAILED;
            case "needs_attention" -> ProviderRefundState.NEEDS_ATTENTION;
            default -> ProviderRefundState.UNKNOWN;
        };
    }
}
