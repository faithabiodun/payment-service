package com.faith.pay.payment.audit;

public enum PaymentEventSource {

    API,

    PAYSTACK_INITIALIZE,

    PROVIDER_INITIALIZE,

    VERIFICATION,

    WEBHOOK,

    REFUND,

    RECONCILIATION
}
