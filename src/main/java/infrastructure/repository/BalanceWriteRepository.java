package infrastructure.repository;

import domain.model.outbox.OutboxEvent;
import domain.model.result.CommandResult;
import infrastructure.metrics.DbMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@ApplicationScoped
public class BalanceWriteRepository {

    @Inject DSLContext dsl;
    @Inject DbMetrics metrics;
    @Inject OutboxRepository outboxRepo;

    public CommandResult reserveCash(long accountId, String requestId, String orderId, BigDecimal amount) {
        long startNanos = System.nanoTime();

        try {
            CommandResult result = dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();

                boolean exists = tx.fetchExists(
                    tx.selectOne().from("account_ledger").where("request_id = ?", requestId)
                );
                if (exists) {
                    metrics.incrementDuplicate("reserve_cash");
                    return CommandResult.duplicate();
                }

                Record rec = tx.fetchOne(
                    "UPDATE accounts " +
                    "SET balance = balance - ?, reserved = reserved + ?, updated_at = now() " +
                    "WHERE account_id = ? AND balance >= ? " +
                    "RETURNING account_id, account_no, balance, reserved, currency, status",
                    amount, amount, accountId, amount
                );

                if (rec == null) {
                    System.out.println(amount);
                    metrics.incrementInsufficient("reserve_cash");
                    return CommandResult.insufficientFunds();
                }

                tx.execute(
                    "INSERT INTO account_ledger (account_id, entry_type, request_id, order_id, amount, created_at) " +
                            "VALUES (?, 'RESERVE', ?, ?, ?, ?::timestamptz)",
                    accountId, requestId, orderId, amount, OffsetDateTime.now()
                );

                String currency = rec.get("currency", String.class);
                OutboxEvent event = OutboxEvent.accountReserved(accountId, amount, requestId, orderId, currency);
                outboxRepo.insert(event);

                return CommandResult.ok();
            });

            long durationNanos = System.nanoTime() - startNanos;
            metrics.recordWrite("reserve_cash", durationNanos);
            return result;

        } catch (DataAccessException e) {
            long durationNanos = System.nanoTime() - startNanos;
            metrics.recordWrite("reserve_cash", durationNanos);
            metrics.incrementError("reserve_cash");
            e.printStackTrace();
            return CommandResult.fail("INTERNAL_ERROR", e.getMessage());
        }
    }

    public CommandResult unreserveCash(long accountId, String requestId) {
        long startNanos = System.nanoTime();

        try {
            CommandResult result = dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();

                var queryResult = tx.fetchOne(
                    "SELECT amount, order_id FROM account_ledger " +
                    "WHERE account_id = ? AND request_id = ? AND entry_type = 'RESERVE'",
                    accountId, requestId
                );

                if (queryResult == null) {
                    return CommandResult.accountNotFound();
                }

                BigDecimal amount = queryResult.get("amount", BigDecimal.class);
                String orderId = queryResult.get("order_id", String.class);
                String unreserveRequestId = "un:" + requestId;

                boolean exists = tx.fetchExists(
                    tx.selectOne().from("account_ledger").where("request_id = ?", unreserveRequestId)
                );
                if (exists) {
                    metrics.incrementDuplicate("unreserve_cash");
                    return CommandResult.duplicate();
                }

                var rec = tx.fetchOne(
                    "UPDATE accounts " +
                    "SET reserved = reserved - ?, balance = balance + ?, updated_at = now() " +
                    "WHERE account_id = ? AND reserved >= ? " +
                    "RETURNING account_id, account_no, balance, reserved, currency, status",
                    amount, amount, accountId, amount
                );

                if (rec == null) {
                    metrics.incrementInsufficient("unreserve_cash");
                    return CommandResult.insufficientFunds();
                }

                tx.execute(
                    "INSERT INTO account_ledger (account_id, entry_type, request_id, order_id, amount, created_at) " +
                    "VALUES (?, 'UNRESERVE', ?, ?, ?, ?::timestamptz)",
                    accountId, unreserveRequestId, orderId, amount, OffsetDateTime.now()
                );

                String currency = rec.get("currency", String.class);
                OutboxEvent event = OutboxEvent.accountReleased(accountId, amount, unreserveRequestId, orderId, currency);
                outboxRepo.insert(event);

                return CommandResult.ok();
            });

            long durationNanos = System.nanoTime() - startNanos;
            metrics.recordWrite("unreserve_cash", durationNanos);
            return result;

        } catch (DataAccessException e) {
            long durationNanos = System.nanoTime() - startNanos;
            metrics.recordWrite("unreserve_cash", durationNanos);
            metrics.incrementError("unreserve_cash");
            return CommandResult.fail("INTERNAL_ERROR", e.getMessage());
        }
    }

    public CommandResult deposit(long accountId, BigDecimal amount, String source) {
        long startNanos = System.nanoTime();
        try {
            CommandResult result = dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();

                Record rec = tx.fetchOne(
                    "UPDATE accounts SET balance = balance + ?, updated_at = now() " +
                    "WHERE account_id = ? " +
                    "RETURNING account_id, account_no, balance, reserved, currency, status",
                    amount, accountId
                );

                if (rec == null) {
                    return CommandResult.accountNotFound();
                }

                String currency = rec.get("currency", String.class);
                BigDecimal newBalance = rec.get("balance", BigDecimal.class);
                BigDecimal reserved = rec.get("reserved", BigDecimal.class);

                // Create outbox event for deposit
                OutboxEvent event = OutboxEvent.balanceUpdated(accountId, newBalance, reserved, currency);
                outboxRepo.insert(event);

                return CommandResult.ok();
            });

            metrics.recordWrite("deposit", System.nanoTime() - startNanos);
            return result;
        } catch (DataAccessException e) {
            metrics.recordWrite("deposit", System.nanoTime() - startNanos);
            metrics.incrementError("deposit");
            return CommandResult.fail("INTERNAL_ERROR", e.getMessage());
        }
    }

    public CommandResult withdraw(long accountId, BigDecimal amount, String destination) {
        long startNanos = System.nanoTime();
        try {
            int count = dsl.execute(
                "UPDATE accounts SET balance = balance - ?, updated_at = now() WHERE account_id = ? AND balance >= ?",
                amount, accountId, amount
            );
            metrics.recordWrite("withdraw", System.nanoTime() - startNanos);
            return count > 0 ? CommandResult.ok() : CommandResult.insufficientFunds();
        } catch (DataAccessException e) {
            metrics.recordWrite("withdraw", System.nanoTime() - startNanos);
            metrics.incrementError("withdraw");
            return CommandResult.fail("INTERNAL_ERROR", e.getMessage());
        }
    }
}
