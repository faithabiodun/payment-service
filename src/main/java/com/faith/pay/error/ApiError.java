package com.faith.pay.error;

import java.time.Instant;

public record ApiError(
        Instant timestamp,
        int status,
        ErrorCode error,
        String message
) {
}
