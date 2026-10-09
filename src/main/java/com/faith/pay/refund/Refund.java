package com.faith.pay.refund;

import com.faith.pay.payment.Payment;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "refunds")
public class Refund {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(name = "payment_id")
    private Payment payment;

    @Column(
            nullable = false,
            unique = true
    )
    private String reference;

    @Column(name = "provider_refund_id")
    private String providerRefundId;

    @Column(
            name = "amount_kobo",
            nullable = false
    )
    private long amountKobo;

    private String reason;

    @Enumerated(EnumType.STRING)
    private RefundStatus status =
            RefundStatus.PENDING;

    @Column(
            name = "idempotency_key",
            unique = true,
            nullable = false
    )
    private String idempotencyKey;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    protected Refund() {
    }

    public Refund(
            Payment payment,
            String reference,
            long amountKobo,
            String reason,
            String idempotencyKey
    ) {
        this.payment = payment;
        this.reference = reference;
        this.amountKobo = amountKobo;
        this.reason = reason;
        this.idempotencyKey =
                idempotencyKey;
    }

    @PrePersist
    void insert() {
        createdAt = Instant.now();
    }

    public void markProcessing(
            String providerRefundId
    ) {
        this.providerRefundId =
                providerRefundId;

        status =
                RefundStatus.PROCESSING;
    }

    public void markSuccess() {
        status = RefundStatus.SUCCESS;
        completedAt = Instant.now();
    }

    public void markFailed() {
        status = RefundStatus.FAILED;
        completedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public Payment getPayment() {
        return payment;
    }

    public long getAmountKobo() {
        return amountKobo;
    }

    public String getReason() {
        return reason;
    }

    public RefundStatus getStatus() {
        return status;
    }

    public String getProviderRefundId() {
        return providerRefundId;
    }
}
