package com.faith.pay.provider.dto;

import java.math.BigInteger;

public record PaystackRefundData(

        BigInteger id,

        String status,

        long amount,

        String currency
) {
}