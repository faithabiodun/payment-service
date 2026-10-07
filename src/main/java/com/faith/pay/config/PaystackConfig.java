package com.faith.pay.config;

import org.springframework.context.annotation.*;
import org.springframework.http.*;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
public class PaystackConfig {

    // Creates a client for sending requests to Paystack.
    @Bean
    WebClient paystackWebClient(
            PaystackProperties properties
    ) {

        return WebClient.builder()
                // Gets Paystack's API address from the application settings.
                .baseUrl(properties.baseUrl())
                // Adds the secret key so Paystack can authenticate requests.
                .defaultHeader(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer "
                                + properties.secretKey()
                )
                // Tells Paystack that request data uses JSON format.
                .defaultHeader(
                        HttpHeaders.CONTENT_TYPE,
                        MediaType.APPLICATION_JSON_VALUE
                )
                .build();
    }
}
