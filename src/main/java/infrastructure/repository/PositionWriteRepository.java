package infrastructure.repository;

import domain.model.result.CommandResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.exception.DataAccessException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@ApplicationScoped
public class PositionWriteRepository {

    @Inject DSLContext dsl;

    public CommandResult reservePosition(long accountId, String symbol, BigDecimal qtyChange, String requestId) {
        try {
            return dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();

                boolean exists = tx.fetchExists(
                    tx.selectOne().from("position_ledger").where("request_id = ?", requestId)
                );
                if (exists) {
                    return CommandResult.duplicate();
                }

                Record rec = tx.fetchOne(
                    "UPDATE positions " +
                    "SET reserved_quantity = reserved_quantity + ?, updated_at = now() " +
                    "WHERE account_id = ? AND symbol = ? AND quantity >= ? " +
                    "RETURNING account_id, symbol, quantity, reserved_quantity, avg_price",
                    qtyChange, accountId, symbol, qtyChange
                );

                if (rec == null) {
                    return CommandResult.insufficientPosition();
                }

                tx.execute(
                    "INSERT INTO position_ledger (account_id, symbol, entry_type, request_id, quantity_change, price, created_at) " +
                    "VALUES (?, ?, 'RESERVE', ?, ?, 0, ?)",
                    accountId, symbol, requestId, qtyChange, OffsetDateTime.now()
                );

                return CommandResult.ok();
            });
        } catch (DataAccessException e) {
            return CommandResult.fail("INTERNAL_ERROR", e.getMessage());
        }
    }

    public CommandResult unreservePosition(long accountId, String requestId) {
        try {
            return dsl.transactionResult(cfg -> {
                DSLContext tx = cfg.dsl();

                Record reserveRec = tx.fetchOne(
                    "SELECT symbol, quantity_change, order_id " +
                    "FROM position_ledger " +
                    "WHERE account_id = ? AND request_id = ? AND entry_type = 'RESERVE'",
                    accountId, requestId
                );

                if (reserveRec == null) {
                    return CommandResult.positionNotFound();
                }

                String symbol = reserveRec.get("symbol", String.class);
                BigDecimal reservedQty = reserveRec.get("quantity_change", BigDecimal.class);
                String orderId = reserveRec.get("order_id", String.class);

                String unreserveRequestId = "un:" + requestId;

                boolean exists = tx.fetchExists(
                    tx.selectOne().from("position_ledger").where("request_id = ?", unreserveRequestId)
                );
                if (exists) {
                    return CommandResult.duplicate();
                }

                tx.execute(
                    "INSERT INTO position_ledger (account_id, symbol, entry_type, request_id, order_id, quantity_change, price, created_at) " +
                    "VALUES (?, ?, 'UNRESERVE', ?, ?, ?, 0, ?)",
                    accountId, symbol, unreserveRequestId, orderId, reservedQty.negate(), OffsetDateTime.now()
                );

                Record rec = tx.fetchOne(
                    "UPDATE positions " +
                    "SET reserved_quantity = reserved_quantity - ?, updated_at = now() " +
                    "WHERE account_id = ? AND symbol = ? AND reserved_quantity >= ? " +
                    "RETURNING account_id, symbol, quantity, reserved_quantity, avg_price",
                    reservedQty, accountId, symbol, reservedQty
                );

                if (rec == null) {
                    return CommandResult.insufficientPosition();
                }

                return CommandResult.ok();
            });
        } catch (DataAccessException e) {
            return CommandResult.fail("INTERNAL_ERROR", e.getMessage());
        }
    }
}
