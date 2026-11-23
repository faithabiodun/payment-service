package com.faith.pay.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Queue depth is read from PostgreSQL so dashboards show durable work, not process-local counters. */
@Component
public class PaymentQueueMetrics implements MeterBinder {
    private static final Logger log = LoggerFactory.getLogger(PaymentQueueMetrics.class);
    private final JdbcTemplate jdbc;

    public PaymentQueueMetrics(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "payment.webhook.inbox.pending", "webhook_inbox",
                "status IN ('RECEIVED', 'RETRY', 'PROCESSING')");
        gauge(registry, "payment.webhook.inbox.dead_letter", "webhook_inbox", "status = 'DEAD_LETTER'");
        gauge(registry, "payment.outbox.pending", "outbox_events",
                "status IN ('READY', 'RETRY', 'PROCESSING')");
        gauge(registry, "payment.outbox.dead_letter", "outbox_events", "status = 'DEAD_LETTER'");
    }

    private void gauge(MeterRegistry registry, String name, String table, String predicate) {
        String sql = "SELECT count(*) FROM " + table + " WHERE " + predicate;
        Gauge.builder(name, jdbc, template -> sampleCount(template, name, sql))
                .description("Durable rows currently matching " + predicate)
                .register(registry);
    }

    private double sampleCount(JdbcTemplate template, String name, String sql) {
        try {
            Long count = template.queryForObject(sql, Long.class);
            return count == null ? 0d : count.doubleValue();
        } catch (DataAccessException failure) {
            log.warn("Could not sample queue depth metric {}: {}", name, failure.getClass().getSimpleName());
            return Double.NaN;
        }
    }
}
