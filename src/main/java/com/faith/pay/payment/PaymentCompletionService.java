package com.faith.pay.payment;

import com.faith.pay.error.*;
import org.springframework.http.HttpStatus;

import com.faith.pay.provider.dto.PaystackTransactionData;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentCompletionService {

    private final PaymentRepository repository;
    private final PaymentAudit audit;

    public PaymentCompletionService(
            PaymentRepository repository,
            PaymentAudit audit
    ) {
        this.repository = repository;
        this.audit = audit;
    }


    @Transactional
    public void applySuccess(
            PaystackTransactionData data,
            String source,
            String rawJson
    ) {

        Payment payment =
                repository
                        .findByReferenceForUpdate(
                                data.reference()
                        )
                        .orElseThrow(() ->
                                new PaymentNotFoundException(data.reference())
                        );


        /*
         * Already applied?
         *
         * Then this is a replay.
         */
        if (payment.getStatus()
                == PaymentStatus.SUCCESS
                ||
                payment.getStatus()
                        == PaymentStatus.PARTIALLY_REFUNDED
                ||
                payment.getStatus()
                        == PaymentStatus.REFUNDED) {

            return;
        }


        if (!"success"
                .equalsIgnoreCase(
                        data.status()
                )) {

            throw new AppException(ErrorCode.PAYMENT_NOT_SUCCESSFUL, HttpStatus.CONFLICT,
                    "Provider transaction " +
                            "is not successful"
            );
        }


        /*
         * A signed provider response still
         * has to match OUR expectations.
         */
        if (payment.getAmountKobo()
                != data.amount()) {

            throw new AppException(ErrorCode.PAYMENT_AMOUNT_MISMATCH, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Payment amount mismatch"
            );
        }


        if (!payment
                .getCurrency()
                .equalsIgnoreCase(
                        data.currency()
                )) {

            throw new AppException(ErrorCode.PAYMENT_CURRENCY_MISMATCH, HttpStatus.UNPROCESSABLE_ENTITY,
                    "Payment currency mismatch"
            );
        }


        PaymentStatus previous =
                payment.getStatus();


        payment.markSuccessful(
                data.id().toString(),
                data.paidAt()
        );


        payment.getOrder()
                .markPaid();


        audit.record(
                payment,
                "PAYMENT_SUCCEEDED",
                previous,
                PaymentStatus.SUCCESS,
                source,
                rawJson
        );
    }
}
