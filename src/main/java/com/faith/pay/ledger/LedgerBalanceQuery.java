package com.faith.pay.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LedgerBalanceQuery {
    private final JdbcTemplate jdbc;
    public LedgerBalanceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /** Positive means net debit; negative means net credit. This value is derived, never stored. */
    public long netDebitMinor(String accountCode, String currency) {
        Long value = jdbc.queryForObject("""
                SELECT COALESCE(SUM(posting.debit_minor - posting.credit_minor), 0)
                FROM ledger_postings posting JOIN ledger_accounts account ON account.id=posting.account_id
                WHERE account.account_code=? AND posting.currency=?
                """, Long.class, accountCode, currency);
        return value == null ? 0 : value;
    }
}
