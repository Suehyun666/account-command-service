package infrastructure.repository;

import domain.model.outbox.OutboxEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jooq.DSLContext;

import java.time.OffsetDateTime;

@ApplicationScoped
public class OutboxRepository {

    @Inject DSLContext dsl;

    public void insert(OutboxEvent event) {
        dsl.execute(
                "INSERT INTO outbox_events " +
                        "(aggregate_type, aggregate_id, event_type, payload, idempotency_key, status, created_at, available_at) " +
                        // [수정 전] ") VALUES (" +  <-- 여기에 괄호가 하나 더 있었습니다.
                        // [수정 후] 아래와 같이 고치세요.
                        "VALUES (" +
                        "  ?, ?, ?, ?, " +
                        "  ?, ?::event_status, ?::timestamptz, ?::timestamptz" +
                        ")",
                event.aggregateType(),
                event.aggregateId(),
                event.eventType(),
                event.payload(),
                event.idempotencyKey(),
                event.status(),
                event.createdAt(),
                event.availableAt()
        );
    }

    public void markPublished(long eventId) {
        dsl.execute(
                "UPDATE outbox_events SET status = 'PUBLISHED'::event_status, published_at = ? WHERE id = ?",
                OffsetDateTime.now(),
                eventId
        );
    }

    public void markFailed(long eventId, String errorMessage, int retryDelaySeconds) {
        dsl.execute(
                "UPDATE outbox_events SET status = 'FAILED'::event_status, error_message = ?, available_at = ? WHERE id = ?",
                truncate(errorMessage, 500),
                OffsetDateTime.now().plusSeconds(retryDelaySeconds),
                eventId
        );
    }

    private String truncate(String msg, int maxLen) {
        if (msg == null) return null;
        return msg.length() <= maxLen ? msg : msg.substring(0, maxLen);
    }
}
