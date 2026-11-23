package com.faith.paymentservice;

import com.faith.pay.observability.PaymentQueueMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentQueueMetricsTests {

    @Test
    void returnsNaNWhenDatabaseIsUnavailable() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Long.class))).thenThrow(new QueryTimeoutException("db down"));
        var registry = new SimpleMeterRegistry();
        new PaymentQueueMetrics(jdbc).bindTo(registry);

        double value = registry.get("payment.outbox.dead_letter").gauge().value();
        assertThat(Double.isNaN(value)).isTrue();
    }
}
