package com.faith.pay.payment;

import com.faith.pay.error.*;
import com.faith.pay.payment.audit.*;
import com.faith.pay.provider.core.ProviderPaymentState;
import com.faith.pay.provider.core.VerifiedPayment;
import com.faith.pay.provider.paystack.PaystackPaymentProvider;
import com.faith.pay.provider.dto.PaystackTransactionData;
import com.faith.pay.outbox.OutboxEventStore;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentSettlementService {

    private final PaymentRepository payments;

    private final PaymentEventStore events;

    private final OutboxEventStore outbox;


    public PaymentSettlementService(
            PaymentRepository payments,
            PaymentEventStore events,
            OutboxEventStore outbox
    ) {

        this.payments = payments;
        this.events = events;
        this.outbox = outbox;
    }


    @Transactional
    public SettlementResult settleSuccess(
            String expectedReference,
            VerifiedPayment provider,
            PaymentEventSource source,
            String rawPayload
    ) {

        /*
         * 1. Lock OUR payment using the reference
         *    we already expected.
         *
         * Don't blindly trust the reference returned
         * by an external system.
         */
        Payment payment =
                payments
                        .findByReferenceForUpdate(
                                expectedReference
                        )
                        .orElseThrow(() ->
                                new PaymentNotFoundException(
                                        expectedReference
                                )
                        );

        if (provider.provider() != payment.getProvider()) {
            throw new AppException(ErrorCode.PROVIDER_RESPONSE_INVALID,
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "Verification result came from a provider that does not own this payment");
        }


        /*
         * 4. Provider must actually say that the
         *    financial transaction succeeded.
         */
        if (provider.state() != ProviderPaymentState.SUCCEEDED) {

            throw new AppException(
                    ErrorCode.PAYMENT_NOT_SUCCESSFUL,

                    org.springframework.http
                            .HttpStatus.CONFLICT,

                    "Provider transaction is "
                            + provider.state()
                            + ", not success"
            );
        }


        /*
         * 5. Provider reference must match
         *    the one WE asked about.
         */
        if (provider.reference() == null
                ||
                !expectedReference.equals(
                        provider.reference()
                )) {

            throw new PaymentReferenceMismatchException(
                    expectedReference,
                    provider.reference()
            );
        }


        /*
         * 6. The amount must match OUR database.
         */
        if (provider.amountMinor()
                != payment.getAmountKobo()) {

            throw new PaymentAmountMismatchException(
                    payment.getAmountKobo(),
                    provider.amountMinor()
            );
        }


        /*
         * 7. Currency must also match.
         */
        if (provider.currency() == null
                ||
                !payment
                        .getCurrency()
                        .equalsIgnoreCase(
                                provider.currency()
                        )) {

            throw new PaymentCurrencyMismatchException(
                    payment.getCurrency(),
                    provider.currency()
            );
        }


        /*
         * 8. Provider transaction id is required
         *    for successful settlement.
         */
        if (provider.providerPaymentId() == null) {

            throw new AppException(
                    ErrorCode.PROVIDER_RESPONSE_INVALID,

                    org.springframework.http
                            .HttpStatus.BAD_GATEWAY,

                    "Successful provider response "
                            + "did not contain transaction id"
            );
        }

        /* Validate provider truth before treating a replay as idempotent. */
        if (payment.getStatus().hasSuccessfulCharge()) {
            if (payment.getProviderTransactionId() != null
                    && !payment.getProviderTransactionId().equals(provider.providerPaymentId())) {
                throw new AppException(ErrorCode.PROVIDER_RESPONSE_INVALID,
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Provider transaction id differs from the settled payment");
            }
            return SettlementResult.ALREADY_APPLIED;
        }

        /* Only open payments may be newly settled as successful. */
        if (!payment.getStatus().canBecomeSuccessful()) {
            throw new UnexpectedPaymentStateException(payment.getReference(), payment.getStatus().name());
        }


        PaymentStatus previous =
                payment.getStatus();


        /*
         * 9. Update the payment.
         */
        payment.markSuccessful(
                provider.providerPaymentId(),

                provider.channel(),

                provider.feeMinor() == null ? 0L : provider.feeMinor(),

                provider.paidAt()
        );


        /*
         * 10. Update the order.
         *
         * The order entity is managed by the
         * same persistence context.
         */
        payment
                .getOrder()
                .markPaid();


        /*
         * 11. Write the financial audit event.
         */
        events.append(
                payment,

                PaymentEventType
                        .PAYMENT_SUCCEEDED,

                previous,

                PaymentStatus.SUCCESS,

                source,

                rawPayload
        );

        outbox.append("PAYMENT", payment.getReference(), "PAYMENT_SUCCEEDED",
                java.util.Map.of("reference", payment.getReference(), "provider", payment.getProvider().name(),
                        "amountMinor", payment.getAmountKobo(), "currency", payment.getCurrency()));


        /*
         * Transaction commits AFTER this method
         * returns successfully.
         */
        return SettlementResult.APPLIED;
    }

    /** Compatibility edge for existing Paystack webhook callers during the adapter migration. */
    @Transactional
    public SettlementResult settleSuccess(String expectedReference, PaystackTransactionData provider,
                                          PaymentEventSource source, String rawPayload) {
        return settleSuccess(expectedReference, PaystackPaymentProvider.toVerifiedPayment(provider), source, rawPayload);
    }
}
