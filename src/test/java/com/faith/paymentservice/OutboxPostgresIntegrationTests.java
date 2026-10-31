package com.faith.paymentservice;

import com.faith.pay.outbox.OutboxEventStore;
import com.faith.pay.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class OutboxPostgresIntegrationTests {
    @Autowired OutboxEventStore store;
    @Autowired OutboxRepository repository;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearOutbox() { jdbc.update("DELETE FROM outbox_events"); }

    @Test
    void eventIsStoredAndCanBeLeasedThenMarkedPublished() {
        store.append("PAYMENT", "PAY-OUTBOX-1", "PAYMENT_SUCCEEDED",
                Map.of("reference", "PAY-OUTBOX-1", "amountMinor", 123L));

        var claimed = repository.claimBatch(1, 60, 8);

        assertThat(claimed).hasSize(1);
        assertThat(claimed.getFirst().payload()).contains("PAY-OUTBOX-1");
        repository.markPublished(claimed.getFirst().id(), claimed.getFirst().attemptCount());
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events WHERE id=?", String.class,
                claimed.getFirst().id())).isEqualTo("PUBLISHED");
    }
}
