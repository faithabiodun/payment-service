package com.faith.pay.webhook;

import com.faith.pay.provider.flutterwave.FlutterwaveV3WebhookVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/webhooks/flutterwave")
public class FlutterwaveWebhookController {
    private final FlutterwaveV3WebhookVerifier verifier;
    private final WebhookInboxService inbox;

    public FlutterwaveWebhookController(FlutterwaveV3WebhookVerifier verifier, WebhookInboxService inbox) {
        this.verifier = verifier;
        this.inbox = inbox;
    }

    @PostMapping
    public ResponseEntity<Void> receive(@RequestBody byte[] rawBody,
                                        @RequestHeader(value = "verif-hash", required = false) String signature) {
        if (!verifier.isValid(signature)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        inbox.accept(com.faith.pay.provider.core.PaymentProviderType.FLUTTERWAVE, rawBody, null, null);
        return ResponseEntity.ok().build();
    }
}
