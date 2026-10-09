package com.faith.pay.payment;

import com.faith.pay.order.*;
import com.faith.pay.error.OrderNotFoundException;
import com.faith.pay.error.PaymentAlreadySuccessfulException;
import com.faith.pay.payment.dto.*;
import com.faith.pay.provider.PaystackClient;
import com.faith.pay.provider.dto.PaystackInitializeData;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class PaymentService {

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaystackClient paystackClient;
    private final PaymentAudit audit;
    private final PaymentCompletionService completionService;

    public PaymentService(
            PaymentRepository paymentRepository,
            OrderRepository orderRepository,
            PaystackClient paystackClient,
            PaymentAudit audit,
            PaymentCompletionService completionService
    ) {
        this.paymentRepository =
                paymentRepository;

        this.orderRepository =
                orderRepository;

        this.paystackClient =
                paystackClient;

        this.audit =
                audit;

        this.completionService =
                completionService;
    }

    @Transactional
    public InitializePaymentResponse initialize(
            InitializePaymentRequest request,
            String idempotencyKey
    ) {

        if (idempotencyKey == null
                || idempotencyKey.isBlank()) {

            throw new IllegalArgumentException(
                    "Idempotency-Key is required"
            );
        }

        // Fast replay check.
        var existing =
                paymentRepository
                        .findByIdempotencyKey(
                                idempotencyKey
                        );

        if (existing.isPresent()) {

            Payment payment =
                    existing.get();

            if (!payment
                    .getOrder()
                    .getId()
                    .equals(request.orderId())) {

                throw new IllegalArgumentException(
                        "Idempotency key was used " +
                                "for another request"
                );
            }

            return InitializePaymentResponse
                    .from(payment);
        }


        /*
         * Lock the order.
         *
         * Two users/threads trying to start payment
         * for the same order cannot both proceed.
         */
        Order order =
                orderRepository
                        .findByIdForUpdate(
                                request.orderId()
                        )
                        .orElseThrow(() ->
                                new OrderNotFoundException(
                                        "Order not found"
                                )
                        );


        /*
         * Recheck AFTER acquiring the lock.
         *
         * Another request may have completed
         * while we were waiting.
         */
        existing =
                paymentRepository
                        .findByIdempotencyKey(
                                idempotencyKey
                        );

        if (existing.isPresent()) {
            return InitializePaymentResponse
                    .from(existing.get());
        }


        if (order.getStatus()
                == OrderStatus.PAID) {

            throw new PaymentAlreadySuccessfulException(
                    "Order is already paid"
            );
        }


        var open =
                paymentRepository
                        .findOpenPaymentForOrder(
                                order.getId()
                        );

        if (open.isPresent()) {

            throw new IllegalStateException(
                    "There is already a payment " +
                            "in progress for this order"
            );
        }


        /*
         * Important:
         * amount comes from OUR order.
         */
        long amountKobo =
                order.getTotalKobo();


        Payment payment =
                new Payment(
                        newReference(),
                        order,
                        amountKobo,
                        idempotencyKey
                );


        paymentRepository.saveAndFlush(
                payment
        );


        PaystackInitializeData provider =
                paystackClient.initialize(
                        order.getCustomerEmail(),
                        amountKobo,
                        payment.getReference()
                );


        payment.markProcessing(
                provider.authorizationUrl(),
                provider.accessCode()
        );


        audit.record(
                payment,
                "INITIALIZED",
                PaymentStatus.PENDING,
                PaymentStatus.PROCESSING,
                "API",
                null
        );


        return InitializePaymentResponse
                .from(payment);
    }


    @Transactional(readOnly = true)
    public PaymentResponse get(
            String reference
    ) {

        Payment payment =
                paymentRepository
                        .findByReference(reference)
                        .orElseThrow(() ->
                                new IllegalArgumentException(
                                        "Payment not found"
                                )
                        );

        return PaymentResponse.from(payment);
    }

    public PaymentResponse verify(
            String reference
    ){
        /*
         * Ask Paystack directly.
         *
         * This HTTP call is outside the transaction
         * used to complete the payment.
         */
        var provider = paystackClient.verify(
                reference
        );

        if("success"
                .equalsIgnoreCase(provider.status())
        ){
            completionService.applySuccess(
                    provider,
                    "VERIFICATION",
                    null
            );
        }

        return get(reference);
    }


    private String newReference() {

        return "PAY-"
                + UUID.randomUUID()
                .toString()
                .replace("-", "");
    }
}
