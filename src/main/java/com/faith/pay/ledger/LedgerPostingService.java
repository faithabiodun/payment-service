package com.faith.pay.ledger;

import com.faith.pay.payment.Payment;
import com.faith.pay.provider.core.PaymentProviderType;
import com.faith.pay.refund.Refund;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;

/** Creates balanced, append-only journal entries inside payment/refund state transactions. */
@Service
public class LedgerPostingService {
    private final JdbcTemplate jdbc;

    public LedgerPostingService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void postPaymentSuccess(Payment payment) {
        long amount = payment.getAmountKobo();
        long fee = payment.getFeesKobo();
        if (amount <= 0 || fee < 0 || fee > amount) {
            throw new IllegalArgumentException("Payment ledger amounts must be positive and fee cannot exceed capture");
        }
        long journalId = createJournal("PAYMENT_SUCCESS:" + payment.getReference(), "PAYMENT",
                payment.getReference(), payment.getCurrency(), "Successful payment capture");
        if (journalId < 0) return;

        String clearing = clearingAccount(payment.getProvider());
        post(journalId, clearing, payment.getCurrency(), amount, 0);
        post(journalId, "SALES_REVENUE", payment.getCurrency(), 0, amount);
        if (fee > 0) {
            post(journalId, "PAYMENT_PROCESSING_FEES", payment.getCurrency(), fee, 0);
            post(journalId, clearing, payment.getCurrency(), 0, fee);
        }
    }

    public void postRefundSuccess(Refund refund) {
        Payment payment = refund.getPayment();
        long amount = refund.getAmountKobo();
        if (amount <= 0) throw new IllegalArgumentException("Refund ledger amount must be positive");
        long journalId = createJournal("REFUND_SUCCESS:" + refund.getReference(), "REFUND",
                refund.getReference(), payment.getCurrency(), "Successful customer refund");
        if (journalId < 0) return;

        String clearing = clearingAccount(payment.getProvider());
        post(journalId, "SALES_RETURNS", payment.getCurrency(), amount, 0);
        post(journalId, clearing, payment.getCurrency(), 0, amount);
    }

    private long createJournal(String eventKey, String aggregateType, String reference,
                               String currency, String description) {
        List<Long> ids = jdbc.query("""
                INSERT INTO journal_entries(event_key, aggregate_type, aggregate_reference, currency, description)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (event_key) DO NOTHING
                RETURNING id
                """, (rs, row) -> rs.getLong(1), eventKey, aggregateType, reference, currency, description);
        return ids.isEmpty() ? -1 : ids.getFirst();
    }

    private void post(long journalId, String accountCode, String currency, long debit, long credit) {
        jdbc.update("""
                INSERT INTO ledger_postings(journal_entry_id, account_id, currency, debit_minor, credit_minor)
                SELECT ?, id, ?, ?, ? FROM ledger_accounts WHERE account_code=? AND active=true
                """, journalId, currency, debit, credit, accountCode);
    }

    private String clearingAccount(PaymentProviderType provider) {
        return switch (provider) {
            case PAYSTACK -> "PROVIDER_CLEARING_PAYSTACK";
            case FLUTTERWAVE -> "PROVIDER_CLEARING_FLUTTERWAVE";
            case STRIPE -> "PROVIDER_CLEARING_STRIPE";
        };
    }
}
