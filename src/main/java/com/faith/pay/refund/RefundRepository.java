package com.faith.pay.refund;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRepository
        extends JpaRepository<Refund, Long> {

    Optional<Refund> findByIdempotencyKey(
            String idempotencyKey
    );

    @Query("""
            select coalesce(sum(r.amountKobo), 0)
            from Refund r
            where r.payment.id = :paymentId
              and r.status in (
                  com.faith.pay.refund.RefundStatus.PENDING,
                  com.faith.pay.refund.RefundStatus.PROCESSING,
                  com.faith.pay.refund.RefundStatus.SUCCESS
              )
            """)
    long totalReservedRefundAmount(
            @Param("paymentId")
            Long paymentId
    );


    List<Refund> findByStatusIn(
            Collection<RefundStatus> statuses
    );
}