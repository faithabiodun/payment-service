package com.faith.paymentservice;

import com.faith.pay.ledger.LedgerBalanceQuery;
import com.faith.pay.order.Order;
import com.faith.pay.order.OrderRepository;
import com.faith.pay.payment.*;
import com.faith.pay.payment.audit.PaymentEventSource;
import com.faith.pay.provider.core.*;
import com.faith.pay.refund.*;
import com.faith.pay.refund.dto.RefundRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LedgerPostgresIntegrationTests {
    @Autowired OrderRepository orders;
    @Autowired PaymentCommandService paymentCommands;
    @Autowired PaymentRepository payments;
    @Autowired PaymentSettlementService settlement;
    @Autowired RefundCommandService refundCommands;
    @Autowired RefundRepository refunds;
    @Autowired RefundStateService refundStates;
    @Autowired LedgerBalanceQuery balances;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void paymentAndRefundCreateBalancedImmutableJournals() {
        String key = UUID.randomUUID().toString();
        long clearingBefore = balances.netDebitMinor("PROVIDER_CLEARING_PAYSTACK", "NGN");
        var order = orders.saveAndFlush(new Order("ledger@example.test", 500_000));
        var reservation = paymentCommands.reserve(order.getId(), "ledger-payment-" + key, PaymentProviderType.PAYSTACK);
        settlement.settleSuccess(reservation.reference(), new VerifiedPayment(PaymentProviderType.PAYSTACK,
                "provider-ledger-" + key, reservation.reference(), ProviderPaymentState.SUCCEEDED,
                500_000, "NGN", "card", 7_500L, Instant.now()), PaymentEventSource.RECONCILIATION, null);

        var refundReservation = refundCommands.reserve(reservation.reference(), new RefundRequest(250_000L, "partial"),
                "ledger-refund-" + key);
        refundStates.applyVerifiedState(refundReservation.refundReference(),
                new VerifiedRefund(PaymentProviderType.PAYSTACK, "refund-ledger-" + key,
                        refundReservation.refundReference(), ProviderRefundState.SUCCEEDED,
                        250_000, "NGN", "processed"), PaymentEventSource.WEBHOOK, "{}");

        assertThat(journalsBalanced(reservation.reference())).isTrue();
        assertThat(journalsBalanced(refundReservation.refundReference())).isTrue();
        assertThat(balances.netDebitMinor("PROVIDER_CLEARING_PAYSTACK", "NGN"))
                .isEqualTo(clearingBefore + 500_000 - 7_500 - 250_000);

        assertThatThrownBy(() -> jdbc.update("UPDATE journal_entries SET description='rewritten' WHERE aggregate_reference=?",
                reservation.reference())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void databaseRejectsUnbalancedJournalAtTransactionCommit() {
        String reference = "unbalanced-" + UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.execute(status -> {
            Long journalId = jdbc.queryForObject("""
                    INSERT INTO journal_entries(event_key,aggregate_type,aggregate_reference,currency,description)
                    VALUES (?, 'PAYMENT', ?, 'NGN', 'bad test journal') RETURNING id
                    """, Long.class, "bad-ledger-" + reference, reference);
            jdbc.update("""
                    INSERT INTO ledger_postings(journal_entry_id,account_id,currency,debit_minor,credit_minor)
                    SELECT ?, id, 'NGN', 100, 0 FROM ledger_accounts WHERE account_code='SALES_REVENUE'
                    """, journalId);
            return null;
        })).isInstanceOf(RuntimeException.class);
    }

    private boolean journalsBalanced(String aggregateReference) {
        return jdbc.queryForObject("""
                SELECT count(*) > 0 AND bool_and(debits = credits)
                FROM (
                    SELECT journal.id, sum(posting.debit_minor) AS debits, sum(posting.credit_minor) AS credits
                    FROM journal_entries journal JOIN ledger_postings posting ON posting.journal_entry_id=journal.id
                    WHERE journal.aggregate_reference=? GROUP BY journal.id
                ) totals
                """, Boolean.class, aggregateReference);
    }
}
