package com.faith.pay.refund;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    Optional<Refund> findByIdempotencyKey(String idempotencyKey);

    @Query("""
            select r
            from Refund r
            join fetch r.payment
            where r.reference = :reference
            """)
    Optional<Refund> findByReference(@Param("reference") String reference);

    Optional<Refund> findByProviderRefundId(String providerRefundId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
            from Refund r
            join fetch r.payment
            where r.reference = :reference
            """)
    Optional<Refund> findByReferenceForUpdate(@Param("reference") String reference);

    @Query(value = """
            SELECT COALESCE(SUM(amount_kobo), 0)
            FROM refunds
            WHERE payment_id = :paymentId
              AND status IN ('PENDING', 'PROCESSING', 'NEEDS_ATTENTION', 'SUCCESS')
            """, nativeQuery = true)
    long sumReservedAmount(@Param("paymentId") Long paymentId);

    List<Refund> findByStatusIn(Collection<RefundStatus> statuses);

    @Query("""
            select r
            from Refund r
            join fetch r.payment p
            where p.reference = :paymentReference
              and r.status in (
                  com.faith.pay.refund.RefundStatus.PENDING,
                  com.faith.pay.refund.RefundStatus.PROCESSING,
                  com.faith.pay.refund.RefundStatus.NEEDS_ATTENTION
              )
            """)
    List<Refund> findNonTerminalByPaymentReference(
            @Param("paymentReference") String paymentReference
    );
}
