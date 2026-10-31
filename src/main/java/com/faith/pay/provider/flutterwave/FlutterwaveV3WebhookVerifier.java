package com.faith.pay.provider.flutterwave;

import com.faith.pay.config.FlutterwaveV3Properties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Flutterwave V3 uses the dashboard secret hash in the verif-hash header. */
@Component
public class FlutterwaveV3WebhookVerifier {
    private final byte[] secret;

    public FlutterwaveV3WebhookVerifier(FlutterwaveV3Properties properties) {
        this.secret = properties.webhookSecretHash() == null
                ? new byte[0]
                : properties.webhookSecretHash().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(String providedHash) {
        if (secret.length == 0 || providedHash == null || providedHash.isBlank()) return false;
        return MessageDigest.isEqual(secret, providedHash.getBytes(StandardCharsets.UTF_8));
    }
}
