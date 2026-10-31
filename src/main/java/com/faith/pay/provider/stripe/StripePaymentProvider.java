package com.faith.pay.provider.stripe;

import com.faith.pay.config.StripeProperties;
import com.faith.pay.error.AppException;
import com.faith.pay.error.ErrorCode;
import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.provider.core.*;
import com.stripe.StripeClient;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.Refund;
import com.stripe.model.StripeCollection;
import com.stripe.model.checkout.Session;
import com.stripe.net.RequestOptions;
import com.stripe.param.RefundCreateParams;
import com.stripe.param.RefundListParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.StreamSupport;

/** Stripe boundary: uses hosted Checkout and translates Stripe records into our provider-neutral types. */
@Component
public class StripePaymentProvider implements PaymentProvider, RefundProvider {
    private static final String REFERENCE_METADATA_KEY = "payment_reference";
    private static final String REFUND_REFERENCE_METADATA_KEY = "local_refund_reference";

    private final StripeClient stripe;
    private final StripeProperties properties;

    public StripePaymentProvider(StripeClient stripe, StripeProperties properties) {
        this.stripe = stripe;
        this.properties = properties;
    }

    @Override
    public PaymentProviderType type() {
        return PaymentProviderType.STRIPE;
    }

    @Override
    public void validateConfiguration() {
        if (blank(properties.secretKey())) {
            throw new AppException(ErrorCode.PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "Stripe is selected but STRIPE_SECRET_KEY is not configured");
        }
    }

    @Override
    public ProviderPaymentResult initialize(ProviderPaymentRequest request) {
        validateConfiguration();
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setClientReferenceId(request.reference())
                .setCustomerEmail(request.customerEmail())
                .putMetadata(REFERENCE_METADATA_KEY, request.reference())
                .setSuccessUrl(redirectUrl(request.callbackUrl(), properties.callbackUrl(), request.reference(), true))
                .setCancelUrl(redirectUrl(null, properties.cancelUrl(), request.reference(), false))
                .setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                        .putMetadata(REFERENCE_METADATA_KEY, request.reference())
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(request.currency().toLowerCase(Locale.ROOT))
                                // Stripe expects an integer in the currency's smallest unit.
                                // The domain's amountMinor is already authoritative.
                                .setUnitAmount(request.amountMinor())
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Payment " + request.reference())
                                        .build())
                                .build())
                        .build())
                .build();

        try {
            Session session = stripe.v1().checkout().sessions().create(params, options(request.idempotencyKey()));
            if (blank(session.getId()) || blank(session.getUrl())) {
                throw new IllegalStateException("Stripe Checkout Session response omitted its id or URL");
            }
            return new ProviderPaymentResult(type(), null, request.reference(), ProviderPaymentState.REQUIRES_ACTION,
                    session.getUrl(), null, session.getId());
        } catch (StripeException failure) {
            throw new ProviderCommunicationException("Could not create Stripe Checkout Session", failure);
        }
    }

    @Override
    public VerifiedPayment verify(String reference, String providerPaymentId) {
        validateConfiguration();
        try {
            if (providerPaymentId != null && providerPaymentId.startsWith("pi_")) {
                return verifyPaymentIntent(reference, stripe.v1().paymentIntents().retrieve(providerPaymentId));
            }

            if (blank(providerPaymentId)) {
                throw new IllegalArgumentException("Stripe verification requires its saved Checkout Session ID");
            }

            Session session = stripe.v1().checkout().sessions().retrieve(providerPaymentId,
                    SessionRetrieveParams.builder().addExpand("payment_intent").build());
            String providerReference = session.getClientReferenceId();
            if (blank(providerReference) && session.getMetadata() != null) {
                providerReference = session.getMetadata().get(REFERENCE_METADATA_KEY);
            }
            if (!reference.equals(providerReference)) {
                throw new IllegalArgumentException("Stripe Checkout Session belongs to a different local payment reference");
            }
            if (!"payment".equals(session.getMode())) {
                throw new IllegalArgumentException("Stripe Checkout Session is not a one-time payment");
            }

            String paymentIntentId = session.getPaymentIntent();
            if (blank(paymentIntentId)) {
                return new VerifiedPayment(type(), session.getId(), reference,
                        sessionState(session.getStatus(), session.getPaymentStatus()),
                        value(session.getAmountTotal()), session.getCurrency(), null, null,
                        instant(session.getCreated()));
            }

            PaymentIntent intent = session.getPaymentIntentObject();
            if (intent == null) intent = stripe.v1().paymentIntents().retrieve(paymentIntentId);
            return verifyPaymentIntent(reference, intent, session);
        } catch (StripeException failure) {
            throw new ProviderCommunicationException("Could not verify payment with Stripe", failure);
        }
    }

    private VerifiedPayment verifyPaymentIntent(String expectedReference, PaymentIntent intent) {
        return verifyPaymentIntent(expectedReference, intent, null);
    }

    private VerifiedPayment verifyPaymentIntent(String expectedReference, PaymentIntent intent, Session session) {
        Map<String, String> metadata = intent.getMetadata();
        String providerReference = metadata == null ? null : metadata.get(REFERENCE_METADATA_KEY);
        if (blank(providerReference) && session != null) providerReference = session.getClientReferenceId();
        if (!expectedReference.equals(providerReference)) {
            throw new IllegalArgumentException("Stripe PaymentIntent belongs to a different local payment reference");
        }

        long amount = intent.getAmountReceived() != null && intent.getAmountReceived() > 0
                ? intent.getAmountReceived()
                : session != null && session.getAmountTotal() != null
                    ? session.getAmountTotal()
                    : value(intent.getAmount());
        String channel = intent.getPaymentMethodTypes() == null || intent.getPaymentMethodTypes().isEmpty()
                ? null : intent.getPaymentMethodTypes().getFirst();
        return new VerifiedPayment(type(), intent.getId(), providerReference, paymentState(intent.getStatus()),
                amount, intent.getCurrency(), channel, null, instant(intent.getCreated()));
    }

    @Override
    public ProviderRefundResult refund(ProviderRefundRequest request) {
        validateConfiguration();
        if (blank(request.providerPaymentId()) || !request.providerPaymentId().startsWith("pi_")) {
            throw new IllegalArgumentException("Stripe refund requires the verified PaymentIntent ID");
        }
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(request.providerPaymentId())
                .setAmount(request.amountMinor())
                .putMetadata(REFUND_REFERENCE_METADATA_KEY, request.refundReference())
                .putMetadata("payment_reference", request.paymentReference())
                .build();
        try {
            Refund refund = stripe.v1().refunds().create(params, options(request.idempotencyKey()));
            return refundResult(refund, request.refundReference());
        } catch (StripeException failure) {
            throw new ProviderCommunicationException("Could not create Stripe refund", failure);
        }
    }

    @Override
    public VerifiedRefund verifyRefund(String providerRefundId, String currency) {
        validateConfiguration();
        try {
            return verifiedRefund(stripe.v1().refunds().retrieve(providerRefundId), null);
        } catch (StripeException failure) {
            throw new ProviderCommunicationException("Could not verify refund with Stripe", failure);
        }
    }

    @Override
    public List<VerifiedRefund> findRefundsForPayment(String providerPaymentId, String localRefundReference,
                                                      String currency) {
        validateConfiguration();
        if (blank(providerPaymentId)) return List.of();
        try {
            StripeCollection<Refund> matches = stripe.v1().refunds().list(RefundListParams.builder()
                    .setPaymentIntent(providerPaymentId).setLimit(100L).build());
            return StreamSupport.stream(matches.autoPagingIterable().spliterator(), false)
                    .filter(refund -> refund.getMetadata() != null
                            && localRefundReference.equals(refund.getMetadata().get(REFUND_REFERENCE_METADATA_KEY)))
                    .map(refund -> verifiedRefund(refund, localRefundReference))
                    .toList();
        } catch (StripeException failure) {
            throw new ProviderCommunicationException("Could not search Stripe refunds", failure);
        }
    }

    private ProviderRefundResult refundResult(Refund refund, String localReference) {
        VerifiedRefund verified = verifiedRefund(refund, localReference);
        return new ProviderRefundResult(type(), verified.providerRefundId(), localReference, verified.state(),
                verified.amountMinor(), verified.currency(), verified.providerStatus());
    }

    private VerifiedRefund verifiedRefund(Refund refund, String localReference) {
        String reference = localReference;
        if (blank(reference) && refund.getMetadata() != null) {
            reference = refund.getMetadata().get(REFUND_REFERENCE_METADATA_KEY);
        }
        return new VerifiedRefund(type(), refund.getId(), reference, refundState(refund.getStatus()),
                value(refund.getAmount()), refund.getCurrency(), refund.getStatus());
    }

    public static ProviderPaymentState paymentState(String status) {
        if (status == null) return ProviderPaymentState.UNKNOWN;
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "succeeded" -> ProviderPaymentState.SUCCEEDED;
            case "requires_action" -> ProviderPaymentState.REQUIRES_ACTION;
            case "processing" -> ProviderPaymentState.PROCESSING;
            case "canceled" -> ProviderPaymentState.CANCELLED;
            case "requires_payment_method", "requires_confirmation", "requires_capture" -> ProviderPaymentState.PENDING;
            default -> ProviderPaymentState.UNKNOWN;
        };
    }

    public static ProviderRefundState refundState(String status) {
        if (status == null) return ProviderRefundState.UNKNOWN;
        return switch (status.toLowerCase(Locale.ROOT)) {
            case "pending" -> ProviderRefundState.PENDING;
            case "succeeded" -> ProviderRefundState.SUCCEEDED;
            case "requires_action" -> ProviderRefundState.NEEDS_ATTENTION;
            case "failed", "canceled" -> ProviderRefundState.FAILED;
            default -> ProviderRefundState.UNKNOWN;
        };
    }

    private ProviderPaymentState sessionState(String status, String paymentStatus) {
        if ("expired".equalsIgnoreCase(status)) return ProviderPaymentState.CANCELLED;
        // A completed paid Session without its PaymentIntent cannot satisfy our
        // settlement identity invariant, so keep it unknown for reconciliation.
        if ("paid".equalsIgnoreCase(paymentStatus)) return ProviderPaymentState.UNKNOWN;
        return ProviderPaymentState.PENDING;
    }

    private RequestOptions options(String idempotencyKey) {
        if (blank(idempotencyKey)) throw new IllegalArgumentException("Stripe idempotency key is required");
        return RequestOptions.builder().setIdempotencyKey(idempotencyKey).build();
    }

    private String redirectUrl(String requested, String configured, String reference, boolean success) {
        String base = !blank(requested) ? requested : configured;
        if (blank(base)) throw new IllegalStateException("Stripe Checkout redirect URL is not configured");
        String separator = base.contains("?") ? "&" : "?";
        String url = base + separator + "reference=" + reference;
        return success ? url + "&session_id={CHECKOUT_SESSION_ID}" : url + "&cancelled=true";
    }

    private static long value(Long amount) { return amount == null ? 0L : amount; }
    private static Instant instant(Long epochSeconds) { return epochSeconds == null ? null : Instant.ofEpochSecond(epochSeconds); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
