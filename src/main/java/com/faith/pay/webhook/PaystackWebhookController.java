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
         * Authentication FIRST.
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
         * Only trusted data reaches
         * business logic.
         */
        service.process(rawBody);


        return ResponseEntity.ok()
                .build();
    }
}