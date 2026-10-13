package com.faith.pay.webhook;

import com.faith.pay.error.AppException;
import com.faith.pay.error.ErrorCode;

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

            throw new AppException(ErrorCode.INVALID_WEBHOOK_SIGNATURE, HttpStatus.UNAUTHORIZED,
                    "Webhook signature is invalid or missing");
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
