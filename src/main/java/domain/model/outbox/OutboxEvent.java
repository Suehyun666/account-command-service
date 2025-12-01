package domain.model.outbox;

import com.hts.generated.events.projection.AccountReservedEvent;
import com.hts.generated.events.projection.AccountFilledEvent;
import com.hts.generated.events.projection.AccountReleasedEvent;
import com.hts.generated.events.projection.AccountBalanceUpdatedEvent;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record OutboxEvent(
        Long id,
        String aggregateType,
        Long aggregateId,
        String eventType,
        byte[] payload,
        String idempotencyKey,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime availableAt
) {

    public static OutboxEvent accountReserved(
            long accountId,
            BigDecimal amount,
            String requestId,
            String orderId,
            String currency
    ) {
        String eventId = UUID.randomUUID().toString();

        AccountReservedEvent event = AccountReservedEvent.newBuilder()
                .setEventId(eventId)
                .setAccountId(accountId)
                .setAmountMicroUnits(amount.multiply(BigDecimal.valueOf(1_000_000)).longValue())
                .setSide("")
                .setSymbol("")
                .setCurrency(currency)
                .setTimestamp(System.currentTimeMillis())
                .build();

        return new OutboxEvent(
                null,
                "ACCOUNT",
                accountId,
                "ACCOUNT_RESERVED",
                event.toByteArray(),
                requestId,
                "PENDING",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    public static OutboxEvent accountFilled(
            long accountId,
            BigDecimal fillAmount,
            String requestId,
            String orderId,
            String currency
    ) {
        String eventId = UUID.randomUUID().toString();

        AccountFilledEvent event = AccountFilledEvent.newBuilder()
                .setEventId(eventId)
                .setAccountId(accountId)
                .setAmountMicroUnits(fillAmount.multiply(BigDecimal.valueOf(1_000_000)).longValue())
                .setSide("")
                .setSymbol("")
                .setCurrency(currency)
                .setTimestamp(System.currentTimeMillis())
                .build();

        return new OutboxEvent(
                null,
                "ACCOUNT",
                accountId,
                "ACCOUNT_FILLED",
                event.toByteArray(),
                requestId,
                "PENDING",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    public static OutboxEvent accountReleased(
            long accountId,
            BigDecimal amount,
            String requestId,
            String orderId,
            String currency
    ) {
        String eventId = UUID.randomUUID().toString();

        AccountReleasedEvent event = AccountReleasedEvent.newBuilder()
                .setEventId(eventId)
                .setAccountId(accountId)
                .setAmountMicroUnits(amount.multiply(BigDecimal.valueOf(1_000_000)).longValue())
                .setSide("")
                .setSymbol("")
                .setCurrency(currency)
                .setTimestamp(System.currentTimeMillis())
                .build();

        return new OutboxEvent(
                null,
                "ACCOUNT",
                accountId,
                "ACCOUNT_RELEASED",
                event.toByteArray(),
                requestId,
                "PENDING",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }

    public static OutboxEvent balanceUpdated(
            long accountId,
            BigDecimal newBalance,
            BigDecimal reserved,
            String currency
    ) {
        String eventId = UUID.randomUUID().toString();

        AccountBalanceUpdatedEvent event = AccountBalanceUpdatedEvent.newBuilder()
                .setEventId(eventId)
                .setAccountId(accountId)
                .setBalanceMicroUnits(newBalance.multiply(BigDecimal.valueOf(1_000_000)).longValue())
                .setReservedMicroUnits(reserved.multiply(BigDecimal.valueOf(1_000_000)).longValue())
                .setCurrency(currency)
                .setTimestamp(System.currentTimeMillis())
                .build();

        return new OutboxEvent(
                null,
                "ACCOUNT",
                accountId,
                "BALANCE_UPDATED",
                event.toByteArray(),
                eventId,
                "PENDING",
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
    }
}
