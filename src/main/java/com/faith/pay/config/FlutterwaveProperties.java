package com.faith.pay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flutterwave-v4")
public record FlutterwaveProperties(String baseUrl, String tokenUrl, String clientId,
                                    String clientSecret, String webhookSecretHash) { }
