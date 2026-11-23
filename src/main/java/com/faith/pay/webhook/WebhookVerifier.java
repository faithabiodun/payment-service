package com.faith.pay.webhook;

import com.faith.pay.config.PaystackProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

@Component
public class WebhookVerifier {

    private static final String ALGORITHM = "HmacSHA512";

    private final byte[] secret;

    public WebhookVerifier(PaystackProperties properties) {
        this.secret = properties.secretKey() == null
                ? new byte[0]
                : properties.secretKey().getBytes(StandardCharsets.UTF_8);
    }

    public boolean isValid(byte[] rawBody, String providedSignature) {
        if (secret.length == 0 || providedSignature == null || providedSignature.isBlank()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            SecretKeySpec key = new SecretKeySpec(secret, ALGORITHM);
            mac.init(key);

            byte[] expected = mac.doFinal(rawBody);
            byte[] provided = HexFormat.of().parseHex(providedSignature);

            return MessageDigest.isEqual(expected, provided);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
