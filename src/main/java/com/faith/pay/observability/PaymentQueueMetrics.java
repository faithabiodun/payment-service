package com.faith.pay.observability;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Queue depth is read from PostgreSQL so dashboards show durable work, not process-local counters. */
@Component
public class PaymentQueueMetrics implements MeterBinder {
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
        Gauge.builder(name, jdbc, template -> template.queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE " + predicate, Long.class))
                .description("Durable rows currently matching " + predicate)
                .register(registry);
    }
}
