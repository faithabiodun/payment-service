package com.faith.pay.reconciliation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "reconciliation")
public record ReconciliationProperties(Duration paymentStaleAfter, Duration abandonAfter,
                                       Duration refundStaleAfter, int maxAttempts, int batchSize) {
}
