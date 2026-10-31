package com.faith.pay.provider.stripe;

import com.faith.pay.config.StripeProperties;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.net.Webhook;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/** Verifies the untouched request payload using Stripe's official signing implementation. */
@Component
public class StripeWebhookVerifier {
    private final String signingSecret;

    public StripeWebhookVerifier(StripeProperties properties) {
        this.signingSecret = properties.webhookSecret();
    }

    public Event verify(byte[] rawBody, String stripeSignature) throws SignatureVerificationException {
        if (rawBody == null || rawBody.length == 0 || stripeSignature == null || stripeSignature.isBlank()
                || signingSecret == null || signingSecret.isBlank()) {
            throw new SignatureVerificationException("Stripe webhook signature or signing secret is missing",
                    stripeSignature);
        }
        return Webhook.constructEvent(new String(rawBody, StandardCharsets.UTF_8), stripeSignature, signingSecret);
    }
}
