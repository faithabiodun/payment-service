package com.faith.pay.config;

import com.stripe.StripeClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StripeClientConfiguration {

    @Bean
    StripeClient stripeClient(StripeProperties properties) {
        StripeClient.StripeClientBuilder builder = StripeClient.builder()
                // ProviderCallExecutor owns retry policy; disable SDK retries to avoid hidden POST replays.
                .setMaxNetworkRetries(0)
                .setConnectTimeout(5_000)
                .setReadTimeout(10_000);
        // A harmless placeholder lets the modular monolith start before Stripe
        // credentials are configured. StripePaymentProvider checks readiness
        // before it reserves locally or makes any API request.
        builder.setApiKey(properties.secretKey() != null && !properties.secretKey().isBlank()
                ? properties.secretKey() : "sk_test_not_configured");
        if (properties.apiBase() != null && !properties.apiBase().isBlank()) {
            builder.setApiBase(properties.apiBase());
        }
        return builder.build();
    }
}
