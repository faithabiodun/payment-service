package com.faith.pay.payment;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.*;

public interface PaymentRepository
        extends JpaRepository<Payment, Long> {

    @Query("""
            select p
            from Payment p
            join fetch p.order
            where p.reference = :reference
            """)
    Optional<Payment> findByReference(
            @Param("reference")
            String reference
    );

    Optional<Payment> findByIdempotencyKey(
            String idempotencyKey
    );

    List<Payment> findByManualReviewRequiredTrue();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select p
            from Payment p
            join fetch p.order
            where p.reference = :reference
            """)
    Optional<Payment> findByReferenceForUpdate(
            @Param("reference")
            String reference
    );

    @Query("""
            select p
            from Payment p
            join fetch p.order
            where p.order.id = :orderId
              and p.status in (
                  com.faith.pay.payment.PaymentStatus.PENDING,
                  com.faith.pay.payment.PaymentStatus.PROCESSING
              )
            """)
    Optional<Payment> findOpenPaymentForOrder(
            @Param("orderId")
            Long orderId
    );

    @Query("""
            select p
            from Payment p
            where p.status = com.faith.pay.payment.PaymentStatus.PROCESSING
              and p.updatedAt < :cutoff
            """)
    List<Payment> findStaleProcessingPayments(
            @Param("cutoff")
            Instant cutoff
    );

    @Query("""
            select p from Payment p join fetch p.order
            where p.status in (com.faith.pay.payment.PaymentStatus.PENDING,
                               com.faith.pay.payment.PaymentStatus.PROCESSING)
              and p.updatedAt < :cutoff and p.manualReviewRequired = false
            order by p.updatedAt asc
            """)
    List<Payment> findReconciliationCandidates(@Param("cutoff") Instant cutoff, Pageable pageable);
}
