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
    private final StripeWebhookService service;

    public StripeWebhookController(StripeWebhookVerifier verifier, StripeWebhookService service) {
        this.verifier = verifier;
        this.service = service;
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
        service.process(verifiedEvent, rawBody);
        return ResponseEntity.ok().build();
    }
}
