package com.faith.pay.provider.dto;

public record PaystackResponse<T>(
        boolean status,
        String message,
        T data
) {
}
