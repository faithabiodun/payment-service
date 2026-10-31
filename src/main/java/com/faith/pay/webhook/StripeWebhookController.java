package com.faith.pay.webhook;

import com.faith.pay.provider.stripe.StripeWebhookVerifier;
import com.stripe.exception.SignatureVerificationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhooks/stripe")
public class StripeWebhookController {
    private final StripeWebhookVerifier verifier;
    private final WebhookInboxService inbox;

    public StripeWebhookController(StripeWebhookVerifier verifier, WebhookInboxService inbox) {
        this.verifier = verifier;
        this.inbox = inbox;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                        @RequestHeader(value = "Stripe-Signature", required = false) String signature) {
        final com.stripe.model.Event verifiedEvent;
        try {
            // Stripe's SDK verifies the original body before it parses the event.
            verifiedEvent = verifier.verify(rawBody, signature);
        } catch (SignatureVerificationException | IllegalArgumentException invalidSignature) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        inbox.accept(com.faith.pay.provider.core.PaymentProviderType.STRIPE, rawBody,
                verifiedEvent.getId(), verifiedEvent.getType());
        return ResponseEntity.ok().build();
    }
}
