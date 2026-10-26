package com.faith.pay.refund.dto;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RefundRequest(
        @Positive Long amountKobo,
        @Size(max = 200) String reason
) {
}
