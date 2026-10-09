package com.faith.pay.provider;

import com.faith.pay.config.PaystackProperties;
import com.faith.pay.error.ProviderUnavailableException;
import com.faith.pay.provider.dto.*;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;

@Component
public class PaystackClient {

    private final WebClient webClient;
    private final PaystackProperties properties;

    public PaystackClient(
            WebClient paystackWebClient,
            PaystackProperties properties
    ) {
        this.webClient = paystackWebClient;
        this.properties = properties;
    }

    public PaystackInitializeData initialize(
            String email,
            long amountKobo,
            String reference
    ) {

        PaystackInitializeRequest request =
                new PaystackInitializeRequest(
                        email,
                        Long.toString(amountKobo),
                        reference,
                        properties.callbackUrl()
                );

        PaystackResponse<PaystackInitializeData>
                response =
                webClient
                        .post()
                        .uri("/transaction/initialize")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(
                                new ParameterizedTypeReference<
                                        PaystackResponse<
                                                PaystackInitializeData
                                                >
                                        >() {
                                }
                        )
                        .block(
                                Duration.ofSeconds(10)
                        );

        if (response == null
                || !response.status()
                || response.data() == null) {

            throw new ProviderUnavailableException(
                    "Paystack initialization failed"
            );
        }

        return response.data();
    }

    public PaystackTransactionData verify(
            String reference
    ) {

        PaystackResponse<PaystackTransactionData>
                response =
                webClient
                        .get()
                        .uri(
                                "/transaction/verify/{reference}",
                                reference
                        )
                        .retrieve()
                        .bodyToMono(
                                new ParameterizedTypeReference<
                                        PaystackResponse<
                                                PaystackTransactionData
                                                >
                                        >() {
                                }
                        )
                        .block(
                                Duration.ofSeconds(10)
                        );

        if (response == null
                || !response.status()
                || response.data() == null) {

            throw new ProviderUnavailableException(
                    "Paystack verification failed"
            );
        }

        return response.data();
    }

    public PaystackRefundData refund(
            String transactionReference,
            long amountKobo,
            String reason
    ) {

        PaystackRefundRequest request =
                new PaystackRefundRequest(
                        transactionReference,
                        amountKobo,
                        reason
                );


        PaystackResponse<PaystackRefundData>
                response =
                webClient
                        .post()
                        .uri("/refund")
                        .bodyValue(request)
                        .retrieve()
                        .bodyToMono(
                                new ParameterizedTypeReference<
                                        PaystackResponse<
                                                PaystackRefundData
                                                >
                                        >() {
                                }
                        )
                        .block(
                                Duration.ofSeconds(10)
                        );


        if (response == null
                || !response.status()
                || response.data() == null) {

            throw new ProviderUnavailableException(
                    "Refund request failed"
            );
        }


        return response.data();
    }
}
