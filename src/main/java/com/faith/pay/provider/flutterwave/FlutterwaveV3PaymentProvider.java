package com.faith.pay.provider.flutterwave;

import com.faith.pay.config.FlutterwaveV3Properties;
import com.faith.pay.error.AppException;
import com.faith.pay.error.ErrorCode;
import com.faith.pay.error.ProviderCommunicationException;
import com.faith.pay.provider.core.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;
import java.util.List;

/** Flutterwave V3 Standard hosted-checkout adapter for the supplied V3 test-key family. */
@Component
public class FlutterwaveV3PaymentProvider implements PaymentProvider, RefundProvider {
    private final WebClient client;
    private final String callbackUrl;
    private final String secretKey;

    public FlutterwaveV3PaymentProvider(FlutterwaveV3Properties properties) {
        this.callbackUrl = properties.callbackUrl();
        this.secretKey = properties.secretKey();
        this.client = WebClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + (properties.secretKey() == null ? "" : properties.secretKey()))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override public PaymentProviderType type() { return PaymentProviderType.FLUTTERWAVE; }

    @Override
    public void validateConfiguration() {
        if (secretKey == null || secretKey.isBlank()) {
            throw new AppException(ErrorCode.PROVIDER_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE,
                    "Flutterwave is selected but FLUTTERWAVE_V3_SECRET_KEY is not configured");
        }
    }

    @Override
    public ProviderPaymentResult initialize(ProviderPaymentRequest request) {
        validateConfiguration();
        Map<String, Object> body = Map.of(
                "tx_ref", request.reference(),
                "amount", majorAmount(request.amountMinor(), request.currency()),
                "currency", request.currency(),
                "redirect_url", request.callbackUrl() == null ? callbackUrl : request.callbackUrl(),
                "customer", Map.of("email", request.customerEmail())
        );
        JsonNode data = post("/payments", body);
        String checkout = text(data, "link");
        if (checkout == null || checkout.isBlank()) throw invalidResponse("Flutterwave hosted link missing");
        return new ProviderPaymentResult(type(), null, request.reference(), ProviderPaymentState.REQUIRES_ACTION,
                checkout, null, null);
    }

    @Override
    public VerifiedPayment verify(String reference, String providerPaymentId) {
        validateConfiguration();
        JsonNode response;
        try {
            response = client.get().uri(uri -> uri.path("/transactions/verify_by_reference")
                            .queryParam("tx_ref", reference).build())
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(10));
        } catch (RuntimeException failure) {
            throw new ProviderCommunicationException("Could not verify payment with Flutterwave", failure);
        }
        JsonNode data = data(response);
        String currency = text(data, "currency");
        BigDecimal rawFee = decimal(data, "app_fee");
        return new VerifiedPayment(type(), text(data, "id"), text(data, "tx_ref"),
                state(text(data, "status")), minorAmount(decimal(data, "amount"), currency, false),
                currency, text(data, "payment_type"),
                rawFee == null ? null : minorAmount(rawFee, currency, true),
                instant(text(data, "created_at")));
    }

    public static ProviderPaymentState state(String value) {
        if (value == null) return ProviderPaymentState.UNKNOWN;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "successful", "success" -> ProviderPaymentState.SUCCEEDED;
            case "pending", "new" -> ProviderPaymentState.PENDING;
            case "failed" -> ProviderPaymentState.FAILED;
            case "cancelled", "canceled" -> ProviderPaymentState.CANCELLED;
            default -> ProviderPaymentState.UNKNOWN;
        };
    }

    @Override
    public ProviderRefundResult refund(ProviderRefundRequest request) {
        validateConfiguration();
        if (request.providerPaymentId() == null || request.providerPaymentId().isBlank()) {
            throw new IllegalArgumentException("Flutterwave refund requires the verified transaction ID");
        }
        Map<String, Object> body = Map.of(
                "amount", majorAmount(request.amountMinor(), request.currency()),
                "comments", "Local refund: " + request.refundReference()
                        + (request.reason() == null ? "" : " | " + request.reason())
        );
        JsonNode data = post("/transactions/{id}/refund", body, request.providerPaymentId());
        String refundId = text(data, "id");
        String rawStatus = text(data, "status");
        return new ProviderRefundResult(type(), refundId, request.refundReference(), refundState(rawStatus),
                minorAmount(decimal(data, "amount_refunded"), request.currency(), false),
                request.currency(), rawStatus);
    }

    @Override
    public VerifiedRefund verifyRefund(String providerRefundId, String currency) {
        validateConfiguration();
        JsonNode response;
        try {
            response = client.get().uri("/refunds/{id}", providerRefundId)
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(10));
        } catch (RuntimeException failure) {
            throw new ProviderCommunicationException("Could not verify refund with Flutterwave", failure);
        }
        JsonNode data = data(response);
        String rawStatus = text(data, "status");
        String id = text(data, "id");
        return new VerifiedRefund(type(), id == null ? providerRefundId : id, null, refundState(rawStatus),
                minorAmount(decimal(data, "amount_refunded"), currency, false), currency, rawStatus);
    }

    @Override
    public List<VerifiedRefund> findRefundsForPayment(String providerPaymentId, String localRefundReference,
                                                      String currency) {
        validateConfiguration();
        JsonNode response;
        try {
            response = client.get().uri(uri -> uri.path("/refunds")
                            .queryParam("from", "2020-01-01")
                            .queryParam("to", java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString())
                            .queryParam("id", providerPaymentId)
                            .queryParam("currency", currency).build())
                    .retrieve().bodyToMono(JsonNode.class).block(Duration.ofSeconds(10));
        } catch (RuntimeException failure) {
            throw new ProviderCommunicationException("Could not search Flutterwave refunds", failure);
        }
        if (response == null || !"success".equalsIgnoreCase(text(response, "status"))) {
            throw invalidResponse("Flutterwave refund search returned an unsuccessful response");
        }
        JsonNode rows = response.path("data");
        if (rows == null || !rows.isArray()) return List.of();
        return java.util.stream.StreamSupport.stream(rows.spliterator(), false)
                .filter(row -> {
                    String comments = text(row, "comments");
                    if (comments == null) comments = text(row, "comment");
                    return comments != null && comments.contains("Local refund: " + localRefundReference);
                })
                .map(row -> {
                    String rawStatus = text(row, "status");
                    String id = text(row, "id");
                    return new VerifiedRefund(type(), id, localRefundReference, refundState(rawStatus),
                            minorAmount(decimal(row, "amount_refunded"), currency, false), currency, rawStatus);
                })
                .toList();
    }

    public static ProviderRefundState refundState(String value) {
        if (value == null) return ProviderRefundState.UNKNOWN;
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "completed" -> ProviderRefundState.PROCESSING;
            case "processing", "pending-momo", "pending-bank-transfer", "pending-mpgs" -> ProviderRefundState.PROCESSING;
            case "completed-bank-transfer", "completed-momo", "completed-mpgs", "completed-offline", "completed-preauth" -> ProviderRefundState.SUCCEEDED;
            case "failed", "failed-momo", "failed-bank-transfer", "failed-mpgs" -> ProviderRefundState.FAILED;
            default -> ProviderRefundState.UNKNOWN;
        };
    }

    private JsonNode post(String path, Object body, Object... uriVariables) {
        try {
            return data(client.post().uri(path, uriVariables).bodyValue(body).retrieve()
                    .bodyToMono(JsonNode.class).block(Duration.ofSeconds(10)));
        } catch (RuntimeException failure) {
            throw new ProviderCommunicationException("Flutterwave request failed", failure);
        }
    }

    private JsonNode data(JsonNode response) {
        if (response == null || !"success".equalsIgnoreCase(text(response, "status"))
                || response.path("data").isMissingNode() || response.path("data").isNull()) {
            throw invalidResponse("Flutterwave returned an unsuccessful or malformed response");
        }
        return response.path("data");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        if (value == null || value.isNull() || value.isMissingNode()) return null;
        if (value.isNumber()) return value.decimalValue();
        if (value.isString()) {
            String raw = value.asString().trim();
            return raw.isEmpty() ? null : new BigDecimal(raw);
        }
        return null;
    }

    private ProviderCommunicationException invalidResponse(String message) {
        return new ProviderCommunicationException(message, null);
    }

    private BigDecimal majorAmount(long minor, String currency) {
        int scale = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
        if (scale < 0) throw new IllegalArgumentException("Currency has no defined minor-unit scale: " + currency);
        return BigDecimal.valueOf(minor, scale);
    }

    private long minorAmount(BigDecimal major, String currency, boolean allowRounding) {
        if (major == null || currency == null || currency.isBlank()) return 0L;
        int scale = Currency.getInstance(currency.trim().toUpperCase(Locale.ROOT)).getDefaultFractionDigits();
        if (scale < 0) throw new IllegalArgumentException("Currency has no defined minor-unit scale: " + currency);
        BigDecimal scaled = major.movePointRight(scale);
        return allowRounding ? scaled.setScale(0, RoundingMode.HALF_UP).longValueExact() : scaled.longValueExact();
    }

    private Instant instant(String value) {
        if (value == null) return null;
        try { return Instant.parse(value); } catch (RuntimeException ignored) { return null; }
    }
}
