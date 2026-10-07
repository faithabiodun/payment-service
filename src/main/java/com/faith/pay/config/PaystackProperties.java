package com.faith.pay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix="paystack")
public record PaystackProperties(
        String baseUrl,
        String secretKey,
        String callbackUrl
) {
}
