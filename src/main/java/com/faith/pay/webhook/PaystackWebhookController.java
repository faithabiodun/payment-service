package com.faith.pay.webhook;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhooks/paystack")
public class PaystackWebhookController {

    private final WebhookVerifier verifier;

    private final WebhookInboxService inbox;


    public PaystackWebhookController(
            WebhookVerifier verifier,
            WebhookInboxService inbox
    ) {

        this.verifier = verifier;

        this.inbox = inbox;
    }


    @PostMapping
    public ResponseEntity<Void> receive(

            @RequestBody
            byte[] rawBody,

            @RequestHeader(
                    value = "x-paystack-signature",
                    required = false
            )
            String signature
    ) {


        /*
         * SECURITY BOUNDARY.
         *
         * Nothing is parsed.
         * Nothing touches the database.
         * Nothing reaches business logic.
         *
         * until authentication succeeds.
         */
        if (!verifier.isValid(
                rawBody,
                signature
        )) {

            return ResponseEntity
                    .status(
                            HttpStatus.UNAUTHORIZED
                    )
                    .build();
        }


        // Acknowledge only after the authenticated payload is durable in PostgreSQL.
        inbox.accept(com.faith.pay.provider.core.PaymentProviderType.PAYSTACK, rawBody, null, null);


        return ResponseEntity
                .ok()
                .build();
    }
}
