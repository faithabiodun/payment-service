package com.faith.pay.config;

import com.stripe.StripeClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StripeClientConfiguration {

    @Bean
    StripeClient stripeClient(StripeProperties properties) {
        StripeClient.StripeClientBuilder builder = StripeClient.builder()
                // We keep retries visible to our application; safe retries can reuse the
                // operation's Stripe idempotency key and are handled by our policy.
                .setMaxNetworkRetries(0);
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
