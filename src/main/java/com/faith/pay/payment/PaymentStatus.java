package com.faith.pay.payment;

public enum PaymentStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILED,
    PARTIALLY_REFUNDED,
    REFUNDED;

    /*
     * Has the original charge already been
     * successfully applied?
     *
     * A refunded payment was still once a
     * successful payment.
     */
    public boolean hasSuccessfulCharge() {

        return this == SUCCESS
                || this == PARTIALLY_REFUNDED
                || this == REFUNDED;
    }


    /*
     * These are the states from which we are
     * willing to accept a provider success.
     */
    public boolean canBecomeSuccessful() {

        return this == PENDING
                || this == PROCESSING;
    }
}
