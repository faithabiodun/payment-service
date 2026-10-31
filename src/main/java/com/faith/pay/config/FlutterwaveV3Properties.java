package com.faith.pay.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flutterwave-v3")
public record FlutterwaveV3Properties(String baseUrl, String publicKey, String secretKey,
                                      String encryptionKey, String webhookSecretHash,
                                      String callbackUrl) { }
