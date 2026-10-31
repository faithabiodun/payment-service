package com.faith.pay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stripe")
public record StripeProperties(String secretKey, String webhookSecret, String apiBase,
                               String callbackUrl, String cancelUrl) { }
