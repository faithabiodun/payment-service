package com.faith.pay.webhook;

import com.faith.pay.provider.core.PaymentProviderType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Extracts stable, provider-specific deduplication material without inventing provider event IDs. */
@Component
public class ProviderWebhookEnvelopeParser {
    private final ObjectMapper mapper;

    public ProviderWebhookEnvelopeParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Receipt parse(PaymentProviderType provider, byte[] rawBody, String stripeEventId, String stripeEventType) {
        try {
            JsonNode root = mapper.readTree(rawBody);
            String eventType = stripeEventType;
            String providerEventId = stripeEventId;
            String objectId = null;
            String status = null;
            if (provider != PaymentProviderType.STRIPE) {
                eventType = text(root, "event");
                if (eventType == null) eventType = text(root, "event.type");
                objectId = text(root, "data.id");
                status = text(root, "data.status");
            }
            if (eventType == null || eventType.isBlank()) eventType = "unknown";

            String sourceKey;
            if (provider == PaymentProviderType.STRIPE && providerEventId != null && !providerEventId.isBlank()) {
                sourceKey = providerEventId;
            } else if (objectId != null) {
                // Flutterwave's current guidance uses event + transaction/refund ID + status.
                // Paystack payloads likewise expose the transaction/refund object ID, not a per-delivery ID.
                sourceKey = eventType + ":" + objectId + ":" + (status == null ? "" : status);
            } else {
                // Exact-payload fallback for signed events with no stable object identifier.
                sourceKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBody));
            }
            String material = provider.name() + ":" + eventType + ":" + sourceKey;
            String dedupeKey = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(material.getBytes(StandardCharsets.UTF_8)));
            return new Receipt(providerEventId, eventType, dedupeKey);
        } catch (Exception e) {
            throw new IllegalArgumentException("Authenticated webhook payload is not valid JSON", e);
        }
    }

    private String text(JsonNode node, String path) {
        for (String part : path.split("\\.")) node = node == null ? null : node.get(part);
        return node == null || node.isNull() ? null : node.asText();
    }

    public record Receipt(String providerEventId, String eventType, String deduplicationKey) {}
}
