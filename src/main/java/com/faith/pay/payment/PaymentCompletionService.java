package com.faith.pay.payment;

import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.dto.PaystackTransactionData;
import org.springframework.stereotype.Service;

/** Compatibility facade; all successful charge state changes use the canonical settlement service. */
@Service
public class PaymentCompletionService {
    private final PaymentSettlementService settlement;

    public PaymentCompletionService(PaymentSettlementService settlement) {
        this.settlement = settlement;
    }

    public void applySuccess(PaystackTransactionData data, PaymentEventSource source, String rawJson) {
        settlement.settleSuccess(data.reference(), data, source, rawJson);
    }
}
