package com.faith.pay.provider.flutterwave;

import com.faith.pay.config.FlutterwaveV3Properties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FlutterwaveV3WebhookVerifierTests {
    @Test
    void checksV3VerifHashConstantTimeAndRejectsUnsetConfiguration() {
        var verifier = new FlutterwaveV3WebhookVerifier(properties("dashboard-hash"));
        assertThat(verifier.isValid("dashboard-hash")).isTrue();
        assertThat(verifier.isValid("wrong-hash")).isFalse();
        assertThat(verifier.isValid(null)).isFalse();
        assertThat(new FlutterwaveV3WebhookVerifier(properties("")).isValid("dashboard-hash")).isFalse();
    }

    private FlutterwaveV3Properties properties(String hash) {
        return new FlutterwaveV3Properties("https://api.flutterwave.com/v3", "public", "test-secret",
                "encryption", hash, "http://localhost/callback");
    }
}
