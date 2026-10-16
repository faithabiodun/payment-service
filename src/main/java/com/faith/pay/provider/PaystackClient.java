package com.faith.pay.provider;

import com.faith.pay.error.*;
import org.springframework.http.HttpStatus;

import com.faith.pay.config.PaystackProperties;
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

        PaystackResponse<PaystackInitializeData> response;
        try {
            response = webClient
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
                        .block(Duration.ofSeconds(10));
        } catch (RuntimeException failure) {
            throw new ProviderCommunicationException("Paystack initialization request failed", failure);
        }

        if (response == null
                || !response.status()
                || response.data() == null) {

            throw new ProviderCommunicationException("Paystack initialization failed");
        }

        return response.data();
    }

    public PaystackTransactionData verify(
            String reference
    ) {

        try {
            PaystackResponse<PaystackTransactionData> response =
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
                            .block(Duration.ofSeconds(10));

            if (response == null) {
                throw new ProviderCommunicationException(
                        "Paystack returned no verification response",
                        null
                );
            }

            /*
             * This status describes whether the Paystack API request itself succeeded.
             * It does not mean the financial payment was successful.
             */
            if (!response.status()) {
                throw new ProviderCommunicationException(
                        "Paystack could not verify transaction: " + response.message(),
                        null
                );
            }

            if (response.data() == null) {
                throw new ProviderCommunicationException(
                        "Paystack verification response contained no transaction data",
                        null
                );
            }

            return response.data();
        } catch (ProviderCommunicationException e) {
            throw e;
        } catch (Exception e) {
            throw new ProviderCommunicationException(
                    "Could not verify payment with Paystack",
                    e
            );
        }
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


        PaystackResponse<PaystackRefundData> response;
        try {
            response = webClient
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
                        .block(Duration.ofSeconds(10));
        } catch (RuntimeException failure) {
            throw new ProviderCommunicationException("Paystack refund request failed", failure);
        }


        if (response == null
                || !response.status()
                || response.data() == null) {

            throw new ProviderCommunicationException("Refund request failed");
        }


        return response.data();
    }
}
