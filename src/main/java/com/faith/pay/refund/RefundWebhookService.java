package com.faith.pay.refund;

import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.pay.webhook.PaystackRefundWebhookData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class RefundWebhookService {

    private static final Logger log = LoggerFactory.getLogger(RefundWebhookService.class);

    private final RefundRepository refunds;
    private final PaystackClient paystack;
    private final RefundStateService states;

    public RefundWebhookService(
            RefundRepository refunds,
            PaystackClient paystack,
            RefundStateService states
    ) {
        this.refunds = refunds;
        this.paystack = paystack;
        this.states = states;
    }

    /** Routes a signed Paystack webhook without opening a transaction around provider I/O. */
    public void process(
            String event,
            PaystackRefundWebhookData data,
            String rawPayload
    ) {
        if (data == null || blank(data.transactionReference())
                || blank(data.status()) || blank(data.currency())) {
            throw new IllegalArgumentException("Refund webhook is missing required fields");
        }

        long amountKobo = parseAmount(data.amount());
        List<Refund> candidates = refunds.findNonTerminalByPaymentReference(
                data.transactionReference()
        ).stream()
                .filter(refund -> refund.getAmountKobo() == amountKobo)
                .toList();

        if (!blank(data.refundReference())) {
            List<Refund> identified = candidates.stream()
                    .filter(refund -> data.refundReference().equals(refund.getReference())
                            || data.refundReference().equals(refund.getProviderRefundId()))
                    .toList();
            if (identified.size() == 1) {
                candidates = identified;
            }
        }

        PaystackRefundData providerState = null;

        if (candidates.size() > 1
                && candidates.stream().allMatch(refund -> refund.getProviderRefundId() != null)) {
            List<Refund> matchingProviderStates = new ArrayList<>();
            Map<String, PaystackRefundData> fetchedStates = new HashMap<>();

            for (Refund candidate : candidates) {
                PaystackRefundData fetched = paystack.fetchRefund(candidate.getProviderRefundId());
                if (matchesEventStatus(event, fetched.status())
                        && fetched.amount() == amountKobo
                        && data.currency().equalsIgnoreCase(fetched.currency())) {
                    matchingProviderStates.add(candidate);
                    fetchedStates.put(candidate.getReference(), fetched);
                }
            }

            if (matchingProviderStates.size() == 1) {
                Refund matched = matchingProviderStates.getFirst();
                providerState = fetchedStates.get(matched.getReference());
                candidates = List.of(matched);
            }
        }

        if (candidates.size() != 1) {
            log.warn("Ignoring {} webhook because it matches {} local refunds for payment {}",
                    event, candidates.size(), data.transactionReference());
            return;
        }

        Refund refund = candidates.getFirst();
        if (providerState == null && refund.getProviderRefundId() != null) {
            // The webhook is a signal; fetch current provider state before applying it.
            providerState = paystack.fetchRefund(refund.getProviderRefundId());
        } else if (providerState == null) {
            // Paystack's documented event contains no required refund id. A unique local
            // payment + amount match lets us safely apply this signed provider state.
            providerState = new PaystackRefundData(
                    null,
                    data.status(),
                    amountKobo,
                    data.currency(),
                    null,
                    null
            );
        }

        states.applyProviderState(
                refund.getReference(),
                providerState,
                PaymentEventSource.WEBHOOK,
                rawPayload
        );
    }

    private boolean matchesEventStatus(String event, String providerStatus) {
        if (providerStatus == null) {
            return false;
        }
        String eventStatus = event.substring("refund.".length()).replace('-', '_');
        return eventStatus.equalsIgnoreCase(providerStatus.replace('-', '_'));
    }

    private long parseAmount(tools.jackson.databind.JsonNode amount) {
        if (amount == null || amount.isNull()) {
            throw new IllegalArgumentException("Refund webhook is missing amount");
        }
        try {
            return new BigInteger(amount.asString()).longValueExact();
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Refund webhook amount is invalid", failure);
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
