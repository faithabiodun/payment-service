package com.faith.pay.webhook;

import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhooks/paystack")
public class PaystackWebhookController {

    private final WebhookVerifier verifier;

    private final WebhookService service;


    public PaystackWebhookController(
            WebhookVerifier verifier,
            WebhookService service
    ) {

        this.verifier = verifier;

        this.service = service;
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


        /*
         * Processing is deliberately small:
         *
         * parse
         * claim event
         * lock payment
         * settle
         * commit
         */
        service.process(
                rawBody
        );


        return ResponseEntity
                .ok()
                .build();
    }
}
