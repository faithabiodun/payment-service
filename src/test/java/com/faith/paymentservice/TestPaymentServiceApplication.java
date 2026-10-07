package com.faith.paymentservice;

import org.springframework.boot.SpringApplication;
import com.faith.PaymentServiceApplication;

public class TestPaymentServiceApplication {

    public static void main(String[] args) {
        SpringApplication.from(PaymentServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
