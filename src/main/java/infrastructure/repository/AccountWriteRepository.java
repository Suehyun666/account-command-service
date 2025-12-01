package infrastructure.repository;

import infrastructure.metrics.DbMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.exception.DataAccessException;

@ApplicationScoped
public class AccountWriteRepository {

    @Inject DSLContext dsl;
    @Inject DbMetrics metrics;

    public boolean createAccount(long accountId) {
        long startNanos = System.nanoTime();
        try {
            int count = dsl.execute(
                    "INSERT INTO accounts (account_id, account_no, balance, reserved, currency, status) " +
                            "VALUES (?, ?, 0, 0, 'USD', 'ACTIVE')",
                    accountId, "ACC" + accountId
            );
            metrics.recordWrite("create_account", System.nanoTime() - startNanos);
            return count > 0;
        } catch (DataAccessException e) {
            metrics.recordWrite("create_account", System.nanoTime() - startNanos);
            if (isDuplicate(e)) {
                metrics.incrementDuplicate("create_account");
                return false;
            }
            metrics.incrementError("create_account");
            return false;
        }
    }

    public boolean deleteAccount(long accountId) {
        long startNanos = System.nanoTime();
        try {
            // Delete child records first to avoid FK constraint violations
            dsl.execute("DELETE FROM position_ledger WHERE account_id = ?", accountId);
            dsl.execute("DELETE FROM positions WHERE account_id = ?", accountId);
            dsl.execute("DELETE FROM account_ledger WHERE account_id = ?", accountId);
            dsl.execute("DELETE FROM processed_events WHERE account_id = ?", accountId);

            // Now delete the account
            int count = dsl.execute(
                    "DELETE FROM accounts WHERE account_id = ?",
                    accountId
            );
            metrics.recordWrite("delete_account", System.nanoTime() - startNanos);
            return count > 0;
        } catch (Exception e) {
            metrics.recordWrite("delete_account", System.nanoTime() - startNanos);
            metrics.incrementError("delete_account");
            return false;
        }
    }

    private boolean isDuplicate(Exception e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof org.postgresql.util.PSQLException ex) {
                if ("23505".equals(ex.getSQLState())) {
                    return true;
                }
            }
            t = t.getCause();
        }
        return false;
    }
}
