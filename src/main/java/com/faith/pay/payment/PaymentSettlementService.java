package com.faith.pay.payment;

import com.faith.pay.error.*;
import com.faith.pay.payment.audit.*;
import com.faith.pay.provider.dto.PaystackTransactionData;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentSettlementService {

    private final PaymentRepository payments;

    private final PaymentEventStore events;


    public PaymentSettlementService(
            PaymentRepository payments,
            PaymentEventStore events
    ) {

        this.payments = payments;
        this.events = events;
    }


    @Transactional
    public SettlementResult settleSuccess(
            String expectedReference,
            PaystackTransactionData provider,
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


        /*
         * 4. Provider must actually say that the
         *    financial transaction succeeded.
         */
        if (!"success"
                .equalsIgnoreCase(
                        provider.status()
                )) {

            throw new AppException(
                    ErrorCode.PAYMENT_NOT_SUCCESSFUL,

                    org.springframework.http
                            .HttpStatus.CONFLICT,

                    "Provider transaction is "
                            + provider.status()
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
        if (provider.amount()
                != payment.getAmountKobo()) {

            throw new PaymentAmountMismatchException(
                    payment.getAmountKobo(),
                    provider.amount()
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
        if (provider.id() == null) {

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
                    && !payment.getProviderTransactionId().equals(provider.id().toString())) {
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
                provider.id().toString(),

                provider.channel(),

                provider.fees() == null ? 0L : provider.fees(),

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


        /*
         * Transaction commits AFTER this method
         * returns successfully.
         */
        return SettlementResult.APPLIED;
    }
}
