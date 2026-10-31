package com.faith.pay.webhook;

import com.faith.pay.error.PaymentNotFoundException;
import com.faith.pay.payment.PaymentRepository;
import com.faith.pay.provider.core.PaymentProviderRegistry;
import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.provider.core.RefundProviderRegistry;
import com.faith.pay.provider.core.VerifiedPayment;
import com.faith.pay.provider.core.VerifiedRefund;
import com.faith.pay.provider.stripe.StripePaymentProvider;
import com.faith.pay.refund.RefundRepository;
import com.stripe.model.Event;
import com.stripe.model.Refund;
import com.stripe.model.StripeObject;
import com.stripe.model.checkout.Session;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/** Routes verified Stripe events to provider verification and shared domain processors. */
@Service
public class StripeWebhookService {
    private final PaymentProviderRegistry payments;
    private final RefundProviderRegistry refundProviders;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final StripePaymentWebhookProcessor paymentProcessor;
    private final StripeRefundWebhookProcessor refundProcessor;

    public StripeWebhookService(PaymentProviderRegistry payments, RefundProviderRegistry refundProviders,
                                PaymentRepository paymentRepository, RefundRepository refundRepository,
                                StripePaymentWebhookProcessor paymentProcessor,
                                StripeRefundWebhookProcessor refundProcessor) {
        this.payments = payments;
        this.refundProviders = refundProviders;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.paymentProcessor = paymentProcessor;
        this.refundProcessor = refundProcessor;
    }

    public void process(Event event, byte[] rawBody) {
        processTrustedEvent(event, rawBody);
    }

    /** Processes an event whose raw bytes were authenticated before durable inbox insertion. */
    public void processTrustedEvent(Event event, byte[] rawBody) {
        String eventType = event.getType();
        String raw = new String(rawBody, StandardCharsets.UTF_8);
        if ("checkout.session.completed".equals(eventType)
                || "checkout.session.async_payment_succeeded".equals(eventType)) {
            processCheckout(event, eventType, raw);
        } else if ("refund.created".equals(eventType) || "refund.updated".equals(eventType)) {
            processRefund(event, eventType, raw);
        }
        // Other signed event types are acknowledged; domain actions are deliberately allow-listed.
    }

    private void processCheckout(Event event, String eventType, String raw) {
        StripeObject object = event.getDataObjectDeserializer().getObject()
                .orElseGet(() -> event.getData().getObject());
        if (!(object instanceof Session session)) {
            throw new IllegalArgumentException("Stripe checkout event did not contain a Checkout Session");
        }
        String reference = session.getClientReferenceId();
        if (reference == null || reference.isBlank()) {
            throw new IllegalArgumentException("Stripe Checkout Session did not contain the local payment reference");
        }
        var payment = paymentRepository.findByReference(reference)
                .orElseThrow(() -> new PaymentNotFoundException(reference));
        if (payment.getProvider() != PaymentProviderType.STRIPE) {
            throw new IllegalArgumentException("Stripe event does not belong to a Stripe payment");
        }
        VerifiedPayment verified = payments.get(PaymentProviderType.STRIPE).verify(reference, session.getId());
        paymentProcessor.apply(event.getId(), eventType, verified, raw);
    }

    private void processRefund(Event event, String eventType, String raw) {
        StripeObject object = event.getDataObjectDeserializer().getObject()
                .orElseGet(() -> event.getData().getObject());
        if (!(object instanceof Refund stripeRefund)) {
            throw new IllegalArgumentException("Stripe refund event did not contain a Refund");
        }

        var local = refundRepository.findByProviderAndProviderRefundId(PaymentProviderType.STRIPE, stripeRefund.getId());
        if (local.isEmpty() && stripeRefund.getMetadata() != null) {
            String reference = stripeRefund.getMetadata().get("local_refund_reference");
            if (reference != null) {
                local = refundRepository.findByReference(reference)
                        .filter(refund -> refund.getProvider() == PaymentProviderType.STRIPE);
            }
        }
        if (local.isEmpty()) return;

        String currency = local.get().getPayment().getCurrency();
        VerifiedRefund verified = refundProviders.get(PaymentProviderType.STRIPE)
                .verifyRefund(stripeRefund.getId(), currency);
        refundProcessor.apply(event.getId(), eventType, local.get().getReference(), verified, raw);
    }
}
