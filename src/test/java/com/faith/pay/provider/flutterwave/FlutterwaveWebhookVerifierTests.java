package com.faith.pay.provider.flutterwave;

import com.faith.pay.config.FlutterwaveProperties;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

class FlutterwaveWebhookVerifierTests {
    private static final String SECRET = "test-webhook-secret";

    @Test
    void acceptsTheV4SignatureOfTheExactRawBody() throws Exception {
        byte[] body = "{\"type\":\"charge.completed\"}".getBytes(StandardCharsets.UTF_8);
        FlutterwaveWebhookVerifier verifier = new FlutterwaveWebhookVerifier(properties(SECRET));
        assertThat(verifier.isValid(body, signature(body))).isTrue();
    }

    @Test
    void rejectsModifiedBodyMissingSignatureAndUnsetSecret() throws Exception {
        byte[] body = "{\"type\":\"charge.completed\"}".getBytes(StandardCharsets.UTF_8);
        FlutterwaveWebhookVerifier verifier = new FlutterwaveWebhookVerifier(properties(SECRET));
        assertThat(verifier.isValid("{}".getBytes(StandardCharsets.UTF_8), signature(body))).isFalse();
        assertThat(verifier.isValid(body, null)).isFalse();
        assertThat(new FlutterwaveWebhookVerifier(properties("")).isValid(body, signature(body))).isFalse();
    }

    private FlutterwaveProperties properties(String secret) {
        return new FlutterwaveProperties("https://example.test", "https://example.test/token", "id", "secret", secret);
    }

    private String signature(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body));
    }
}
