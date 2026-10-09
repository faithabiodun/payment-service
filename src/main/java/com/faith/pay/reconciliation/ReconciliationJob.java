package com.faith.pay.reconciliation;

import com.faith.pay.payment.*;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;
import java.util.List;

@Component
public class ReconciliationJob {

    private final PaymentRepository repository;
    private final PaymentService paymentService;

    public ReconciliationJob(
            PaymentRepository repository,
            PaymentService paymentService
    ) {
        this.repository = repository;
        this.paymentService = paymentService;
    }


    @Scheduled(
            fixedDelayString = "PT5M"
    )
    public void reconcile() {

        Instant cutoff =
                Instant.now()
                        .minus(
                                10,
                                java.time.temporal
                                        .ChronoUnit.MINUTES
                        );


        List<Payment> stale =
                repository
                        .findStaleProcessingPayments(
                                cutoff
                        );


        for (Payment payment : stale) {

            try {

                paymentService.verify(
                        payment.getReference()
                );

            } catch (Exception e) {

                /*
                 * Production:
                 * structured log +
                 * metric +
                 * alert.
                 */
            }
        }
    }
}