package infrastructure.repository;

import domain.model.command.ApplyFillCommand;
import domain.model.outbox.OutboxEvent;
import domain.model.result.CommandResult;
import infrastructure.metrics.DbMetrics;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

@ApplicationScoped
public class FillWriteRepository {

    @Inject DSLContext dsl;
    @Inject DbMetrics metrics;
    @Inject OutboxRepository outboxRepo;

    public CommandResult applyFill(ApplyFillCommand cmd) {
        long startNanos = System.nanoTime();
        String operation = cmd.isBuy() ? "apply_buy_fill" : "apply_sell_fill";

        try {
            CommandResult result = dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();

                boolean exists = tx.fetchExists(
                    tx.selectOne().from("processed_events").where("event_id = ?", cmd.requestId())
                );
                if (exists) {
                    metrics.incrementDuplicate(operation);
                    return CommandResult.alreadyProcessed();
                }

                tx.execute(
                    "INSERT INTO processed_events (event_id, event_type, account_id, processed_at) " +
                    "VALUES (?, 'ORDER_FILL', ?, ?)",
                    cmd.requestId(), cmd.accountId(), OffsetDateTime.now()
                );

                if (cmd.isBuy()) {
                    return processBuyFill(tx, cmd);
                } else {
                    return processSellFill(tx, cmd);
                }
            });

            long durationNanos = System.nanoTime() - startNanos;
            metrics.recordWrite(operation, durationNanos);
            return result;

        } catch (DataAccessException e) {
            long durationNanos = System.nanoTime() - startNanos;
            metrics.recordWrite(operation, durationNanos);
            metrics.incrementError(operation);
            return CommandResult.fail("INTERNAL_ERROR", e.getMessage());
        }
    }

    private CommandResult processBuyFill(DSLContext tx, ApplyFillCommand cmd) {
        Record accRec = tx.fetchOne(
            "UPDATE accounts " +
            "SET reserved = reserved - ?, updated_at = now() " +
            "WHERE account_id = ? AND reserved >= ? " +
            "RETURNING account_id, account_no, balance, reserved, currency, status",
            cmd.fillAmount(), cmd.accountId(), cmd.fillAmount()
        );

        if (accRec == null) {
            metrics.incrementInsufficient("apply_buy_fill");
            return CommandResult.insufficientFunds();
        }

        tx.execute(
            "INSERT INTO account_ledger (account_id, entry_type, request_id, order_id, amount, created_at) " +
            "VALUES (?, 'BUY_FILL', ?, ?, ?, ?)",
            cmd.accountId(), cmd.requestId(), cmd.orderId(), cmd.fillAmount().negate(), OffsetDateTime.now()
        );

        BigDecimal avgPrice = cmd.fillAmount().divide(BigDecimal.valueOf(cmd.fillQuantity()), 2, RoundingMode.HALF_UP);

        tx.execute(
            "INSERT INTO position_ledger (account_id, symbol, entry_type, request_id, order_id, quantity_change, price, created_at) " +
            "VALUES (?, ?, 'BUY', ?, ?, ?, ?, ?)",
            cmd.accountId(), cmd.symbol(), cmd.requestId(), cmd.orderId(), cmd.fillQuantity(), avgPrice, OffsetDateTime.now()
        );

        tx.execute(
            "INSERT INTO positions (account_id, symbol, quantity, reserved_quantity, avg_price) " +
            "VALUES (?, ?, ?, 0, ?) " +
            "ON CONFLICT (account_id, symbol) DO UPDATE " +
            "SET quantity = positions.quantity + EXCLUDED.quantity, " +
            "    avg_price = ((positions.quantity * positions.avg_price) + (EXCLUDED.quantity * EXCLUDED.avg_price)) / (positions.quantity + EXCLUDED.quantity), " +
            "    updated_at = now()",
            cmd.accountId(), cmd.symbol(), cmd.fillQuantity(), avgPrice
        );

        String currency = accRec.get("currency", String.class);
        OutboxEvent event = OutboxEvent.accountFilled(cmd.accountId(), cmd.fillAmount(), cmd.requestId(), cmd.orderId(), currency);
        outboxRepo.insert(event);

        return CommandResult.ok();
    }

    private CommandResult processSellFill(DSLContext tx, ApplyFillCommand cmd) {
        Record posRec = tx.fetchOne(
            "UPDATE positions " +
            "SET reserved_quantity = reserved_quantity - ?, quantity = quantity - ?, updated_at = now() " +
            "WHERE account_id = ? AND symbol = ? AND reserved_quantity >= ? AND quantity >= ? " +
            "RETURNING account_id, symbol, quantity, avg_price",
            cmd.fillQuantity(), cmd.fillQuantity(), cmd.accountId(), cmd.symbol(), cmd.fillQuantity(), cmd.fillQuantity()
        );

        if (posRec == null) {
            metrics.incrementInsufficient("apply_sell_fill");
            return CommandResult.insufficientPosition();
        }

        BigDecimal avgPrice = cmd.fillAmount().divide(BigDecimal.valueOf(cmd.fillQuantity()), 2, RoundingMode.HALF_UP);

        tx.execute(
            "INSERT INTO position_ledger (account_id, symbol, entry_type, request_id, order_id, quantity_change, price, created_at) " +
            "VALUES (?, ?, 'SELL', ?, ?, ?, ?, ?)",
            cmd.accountId(), cmd.symbol(), cmd.requestId(), cmd.orderId(), -cmd.fillQuantity(), avgPrice, OffsetDateTime.now()
        );

        tx.execute(
            "INSERT INTO account_ledger (account_id, entry_type, request_id, order_id, amount, created_at) " +
            "VALUES (?, 'SELL_FILL', ?, ?, ?, ?)",
            cmd.accountId(), cmd.requestId(), cmd.orderId(), cmd.fillAmount(), OffsetDateTime.now()
        );

        Record accRec = tx.fetchOne(
            "UPDATE accounts " +
            "SET balance = balance + ?, updated_at = now() " +
            "WHERE account_id = ? " +
            "RETURNING account_id, account_no, balance, reserved, currency, status",
            cmd.fillAmount(), cmd.accountId()
        );

        if (accRec == null) {
            return CommandResult.accountNotFound();
        }

        String currency = accRec.get("currency", String.class);
        OutboxEvent event = OutboxEvent.accountFilled(cmd.accountId(), cmd.fillAmount(), cmd.requestId(), cmd.orderId(), currency);
        outboxRepo.insert(event);

        return CommandResult.ok();
    }
}
