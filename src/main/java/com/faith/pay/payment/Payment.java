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
            Instant paidAt
    ) {
        this.providerTransactionId =
                providerTransactionId;

        this.paidAt = paidAt;

        this.status =
                PaymentStatus.SUCCESS;
    }

    public void markPartiallyRefunded() {
        status =
                PaymentStatus.PARTIALLY_REFUNDED;
    }

    public void markRefunded() {
        status =
                PaymentStatus.REFUNDED;
    }

    public void addRefundedKobo(long amount) {

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

    public long getRefundedKobo() {
        return refundedKobo;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
