package com.faith.pay.provider.flutterwave;

import com.faith.pay.config.FlutterwaveProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

/** Verifies Flutterwave V4's HMAC-SHA256 signature over exact raw request bytes. */
@Component
public class FlutterwaveWebhookVerifier {
    private static final String ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public FlutterwaveWebhookVerifier(FlutterwaveProperties properties) {
        this.secret = properties.webhookSecretHash() == null
                ? new byte[0]
                : properties.webhookSecretHash().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] rawBody, String signature) {
        if (secret.length == 0 || signature == null || signature.isBlank() || rawBody == null) return false;
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            byte[] expected = Base64.getEncoder().encode(mac.doFinal(rawBody));
            byte[] provided = signature.getBytes(StandardCharsets.US_ASCII);
            return MessageDigest.isEqual(expected, provided);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }
}
