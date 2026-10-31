package com.faith.paymentservice;

import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.webhook.WebhookInboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class WebhookInboxPostgresIntegrationTests {
    @Autowired WebhookInboxRepository inbox;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void clearInbox() {
        jdbc.update("DELETE FROM webhook_inbox");
    }

    @Test
    void duplicateAuthenticatedEventHasOneDurableInboxRow() {
        assertThat(inbox.insert(PaymentProviderType.STRIPE, "evt_repeat", "key-repeat",
                "checkout.session.completed", "{\"id\":\"evt_repeat\"}")).isTrue();
        assertThat(inbox.insert(PaymentProviderType.STRIPE, "evt_repeat", "key-repeat",
                "checkout.session.completed", "{\"id\":\"evt_repeat\"}")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM webhook_inbox", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentWorkersClaimDifferentRowsWithSkipLocked() throws Exception {
        for (int i = 0; i < 2; i++) {
            inbox.insert(PaymentProviderType.PAYSTACK, null, "key-" + i, "charge.success", "{}");
        }

        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> inbox.claimBatch(1, 60, 8));
            Future<?> second = executor.submit(() -> inbox.claimBatch(1, 60, 8));
            var firstClaim = (java.util.List<?>) first.get();
            var secondClaim = (java.util.List<?>) second.get();
            assertThat(firstClaim).hasSize(1);
            assertThat(secondClaim).hasSize(1);
            assertThat(((com.faith.pay.webhook.WebhookInboxEvent) firstClaim.getFirst()).id())
                    .isNotEqualTo(((com.faith.pay.webhook.WebhookInboxEvent) secondClaim.getFirst()).id());
        }
    }
}
