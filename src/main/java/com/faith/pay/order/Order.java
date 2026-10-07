package com.faith.pay.order;

import jakarta.persistence.*;

@Entity
@Table(name="orders")
public class Order {
    @Id
    @GeneratedValue(strategy=GenerationType.IDENTITY)
    private long id;

    @Column(
            name="customer_email",
            nullable = false
    )
    private String customerEmail;

    @Column(
            name="total_kobo",
            nullable = false
    )
    private long totalKobo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status = OrderStatus.UNPAID;

    protected Order() {}

    public Order(String customerEmail, long totalKobo) {
        this.customerEmail = customerEmail;
        this.totalKobo = totalKobo;
    }

    public void markPaid(){
        if (status==OrderStatus.PAID){
            return;      //idempotent method
        }
        status = OrderStatus.PAID;
    }
}
