package com.faith.pay.refund;

import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.provider.core.ProviderCallExecutor;
import com.faith.pay.provider.core.ProviderCallOperation;
import com.faith.pay.provider.dto.PaystackRefundData;
import com.faith.pay.webhook.PaystackRefundWebhookData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final ProviderCallExecutor calls;

    @Autowired
    public RefundWebhookService(
            RefundRepository refunds,
            PaystackClient paystack,
            RefundStateService states,
            ProviderCallExecutor calls
    ) {
        this.refunds = refunds;
        this.paystack = paystack;
        this.states = states;
        this.calls = calls;
    }

    public RefundWebhookService(
            RefundRepository refunds,
            PaystackClient paystack,
            RefundStateService states
    ) {
        this(refunds, paystack, states, null);
    }

    /**
     * Routes a signed Paystack webhook without opening a transaction around provider I/O.
     *
     * @return {@code true} if the webhook was applied or safely ignored; {@code false} if resolution
     *         is ambiguous because pending refunds are still in flight and should be retried later.
     */
    public boolean process(
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
            // When the webhook specifies a refund reference, never fall back to a different local refund.
            candidates = candidates.stream()
                    .filter(refund -> data.refundReference().equals(refund.getReference())
                            || data.refundReference().equals(refund.getProviderRefundId()))
                    .toList();
        }

        PaystackRefundData providerState = null;

        if (candidates.size() > 1
                && candidates.stream().allMatch(refund -> refund.getProviderRefundId() != null)) {
            List<Refund> matchingProviderStates = new ArrayList<>();
            Map<String, PaystackRefundData> fetchedStates = new HashMap<>();

            for (Refund candidate : candidates) {
                PaystackRefundData fetched = fetchRefund(candidate.getProviderRefundId());
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
            boolean deferrable = candidates.size() > 1
                    && candidates.stream().anyMatch(r -> r.getProviderRefundId() == null);
            log.warn("Cannot uniquely resolve {} webhook (matched {} local refunds) for payment {}; defer={}",
                    event, candidates.size(), data.transactionReference(), deferrable);
            return !deferrable;
        }

        Refund refund = candidates.getFirst();
        if (providerState == null && refund.getProviderRefundId() != null) {
            // The webhook is a signal; fetch current provider state before applying it.
            providerState = fetchRefund(refund.getProviderRefundId());
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
        return true;
    }

    private PaystackRefundData fetchRefund(String providerRefundId) {
        if (calls == null) {
            return paystack.fetchRefund(providerRefundId);
        }
        return calls.execute(PaymentProviderType.PAYSTACK, ProviderCallOperation.READ_ONLY,
                () -> paystack.fetchRefund(providerRefundId));
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
