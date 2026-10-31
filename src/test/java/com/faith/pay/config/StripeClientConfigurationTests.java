package com.faith.pay.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StripeClientConfigurationTests {
    @Test
    void applicationCanStartWithoutStripeCredentialsUntilStripeIsSelected() {
        var client = new StripeClientConfiguration().stripeClient(new StripeProperties(
                "", "", "https://api.stripe.com", "http://localhost/success", "http://localhost/cancel"));

        assertThat(client).isNotNull();
    }
}
