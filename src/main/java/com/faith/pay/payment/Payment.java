package com.faith.pay.payment;

import com.faith.pay.order.Order;

import jakarta.persistence.*;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
            nullable = false,
            unique = true
    )
    private String reference;

    @ManyToOne(
            fetch = FetchType.LAZY,
            optional = false
    )
    @JoinColumn(name = "order_id")
    private Order order;

    @Column(
            name = "amount_kobo",
            nullable = false
    )
    private long amountKobo;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String currency = "NGN";

    @Column(name="channel")
    private String channel;

    @Column(name = "fees_kobo")
    private long feesKobo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status =
            PaymentStatus.PENDING;

    @Column(
            name = "idempotency_key",
            nullable = false,
            unique = true
    )
    private String idempotencyKey;

    @Column(name = "authorization_url")
    private String authorizationUrl;

    @Column(name = "access_code")
    private String accessCode;

    @Column(name = "provider_transaction_id")
    private String providerTransactionId;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(
            name = "refunded_kobo",
            nullable = false
    )
    private long refundedKobo;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "reconciliation_attempts", nullable = false)
    private int reconciliationAttempts;

    @Column(name = "last_reconciled_at")
    private Instant lastReconciledAt;

    @Column(name = "manual_review_required", nullable = false)
    private boolean manualReviewRequired;

    @Version
    private long version;

    protected Payment() {
    }

    public Payment(
            String reference,
            Order order,
            long amountKobo,
            String idempotencyKey
    ) {
        this.reference = reference;
        this.order = order;
        this.amountKobo = amountKobo;
        this.idempotencyKey = idempotencyKey;
    }

    @PrePersist
    void beforeInsert() {
        Instant now = Instant.now();

        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Instant.now();
    }

    public void markProcessing(
            String authorizationUrl,
            String accessCode
    ) {
        this.authorizationUrl = authorizationUrl;
        this.accessCode = accessCode;
        this.status = PaymentStatus.PROCESSING;
    }

    public void markSuccessful(
            String providerTransactionId,
            String channel,
            long feesKobo,
            Instant paidAt
    ) {
        this.providerTransactionId =
                providerTransactionId;

        this.paidAt = paidAt;

        this.status =
                PaymentStatus.SUCCESS;

        this.channel = channel;

        this.feesKobo = feesKobo;
    }

    public void markPartiallyRefunded() {
        status =
                PaymentStatus.PARTIALLY_REFUNDED;
    }

    public void markRefunded() {
        status =
                PaymentStatus.REFUNDED;
    }

    public void markFailed() {
        if (!status.isOpen()) throw new IllegalStateException("Only open payments can be marked failed");
        status = PaymentStatus.FAILED;
    }

    public void markAbandoned() {
        if (!status.isOpen()) throw new IllegalStateException("Only open payments can be marked abandoned");
        status = PaymentStatus.ABANDONED;
    }

    public void recordReconciliationAttempt() {
        reconciliationAttempts++;
        lastReconciledAt = Instant.now();
    }

    public void requireManualReview() { manualReviewRequired = true; }

    public void addRefundedKobo(long amount) {

        if (amount <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive");
        }

        if (status != PaymentStatus.SUCCESS
                && status != PaymentStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException(
                    "Only successful payments can be refunded"
            );
        }

        if (refundedKobo < 0 || refundedKobo > amountKobo) {
            throw new IllegalStateException(
                    "Refunded amount is outside the payment amount"
            );
        }

        if (amount > amountKobo - refundedKobo) {
            throw new IllegalStateException("Refund would exceed payment amount");
        }

        refundedKobo += amount;

        if (refundedKobo == amountKobo) {
            markRefunded();
        } else {
            markPartiallyRefunded();
        }
    }

    public Long getId() {
        return id;
    }

    public String getReference() {
        return reference;
    }

    public Order getOrder() {
        return order;
    }

    public long getAmountKobo() {
        return amountKobo;
    }

    public String getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getAuthorizationUrl() {
        return authorizationUrl;
    }

    public String getAccessCode() {
        return accessCode;
    }

    public String getProviderTransactionId() {
        return providerTransactionId;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public long getRefundedKobo() {
        return refundedKobo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getFeesKobo() {
        return feesKobo;
    }

    public String getChannel() {
        return channel;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public int getReconciliationAttempts() { return reconciliationAttempts; }
    public Instant getLastReconciledAt() { return lastReconciledAt; }
    public boolean isManualReviewRequired() { return manualReviewRequired; }
}
