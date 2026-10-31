package com.faith.pay.webhook;

import com.faith.pay.provider.flutterwave.FlutterwaveV3WebhookVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhooks/flutterwave")
public class FlutterwaveWebhookController {
    private final FlutterwaveV3WebhookVerifier verifier;
    private final FlutterwaveWebhookService service;

    public FlutterwaveWebhookController(FlutterwaveV3WebhookVerifier verifier, FlutterwaveWebhookService service) {
        this.verifier = verifier;
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                        @RequestHeader(value = "verif-hash", required = false) String signature) {
        if (!verifier.isValid(signature)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        service.process(rawBody);
        return ResponseEntity.ok().build();
    }
}
