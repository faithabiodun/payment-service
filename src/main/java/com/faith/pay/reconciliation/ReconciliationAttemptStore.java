package com.faith.pay.reconciliation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReconciliationAttemptStore {
    private final JdbcTemplate jdbc;
    public ReconciliationAttemptStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void append(String type, String reference, String outcome, String providerStatus, String details) {
        jdbc.update("""
                INSERT INTO reconciliation_attempts(target_type, target_reference, outcome, provider_status, details)
                VALUES (?, ?, ?, ?, ?)
                """, type, reference, outcome, providerStatus, details);
    }
}
