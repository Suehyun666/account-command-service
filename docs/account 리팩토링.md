핵심:
1. Redis set은 비효율적 → 모델 안에 toRedisArgs() 같은 메서드로 미리
   포맷팅해서 1회 HMSET으로 때려박기
2. Exception은 거의 안 일어나야 함 → DB 제약/비즈니스 검증은 트랜잭션 시작
   전에 먼저 체크해서, 롤백/재시도 빈도 최소화
3. Kafka 병목도 막아야 함 → Outbox Worker는 배치 비동기 send + flush
   방식으로, Producer 처리량 최대한 뽑아내기

지금부터는 "진짜 성능 나오는" 코드로 다시 박는다.

  ---
1. Domain Model (Redis Projection용 직렬화 포함)

AccountSnapshot.java

package com.hts.account.domain.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public record AccountSnapshot(
long accountId,
BigDecimal balance,
BigDecimal reserved,
String currency,
String status,
List<PositionSnapshot> positions
) {
/**
* Redis HMSET용 args 생성 (1번에 박기 위해)
* key = "account:{accountId}"
* fields = balance, reserved, currency, status
*/
public List<String> toRedisArgs(String keyPrefix) {
String key = keyPrefix + accountId;
List<String> args = new ArrayList<>();
args.add(key);
args.add("balance");
args.add(balance.toPlainString());
args.add("reserved");
args.add(reserved.toPlainString());
args.add("currency");
args.add(currency);
args.add("status");
args.add(status);
return args;
}

      /**
       * Position도 함께 Redis에 저장하고 싶으면
       * key = "pos:{accountId}"
       * field = symbol, value = "qty|reserved|avgPrice"
       */
      public List<String> positionsToRedisArgs(String keyPrefix) {
          if (positions == null || positions.isEmpty()) {
              return List.of();
          }

          String posKey = keyPrefix + accountId;
          List<String> args = new ArrayList<>();
          args.add(posKey);

          for (PositionSnapshot p : positions) {
              args.add(p.symbol());
              args.add(p.quantity().toPlainString() + "|"
                      + p.reservedQuantity().toPlainString() + "|"
                      + p.avgPrice().toPlainString());
          }
          return args;
      }
}

PositionSnapshot.java

package com.hts.account.domain.model;

import java.math.BigDecimal;

public record PositionSnapshot(
long accountId,
String symbol,
BigDecimal quantity,
BigDecimal reservedQuantity,
BigDecimal avgPrice
) {}

  ---
2. AccountCommandService (Exception 최소화 + 트랜잭션 전 Pre-check)

핵심 전략:

- 트랜잭션 밖에서 먼저 간단한 체크 (계좌 존재, 상태 활성화 등)
- 트랜잭션 안에서는 FOR UPDATE + 최종 검증 + 빠른 UPDATE만 수행
- 비즈니스 실패는 Exception 대신 Result 객체로 반환 (롤백 비용 제로)

package com.hts.account.domain;

import com.hts.account.domain.model.AccountSnapshot;
import com.hts.account.domain.model.PositionSnapshot;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.hts.account.jooq.tables.Accounts.ACCOUNTS;
import static com.hts.account.jooq.tables.AccountLedger.ACCOUNT_LEDGER;
import static com.hts.account.jooq.tables.OutboxEvents.OUTBOX_EVENTS;
import static com.hts.account.jooq.tables.Positions.POSITIONS;

@ApplicationScoped
public class AccountCommandService {

      @Inject
      DSLContext dsl;

      // ──────────────────────────────────────────────────────────
      // Reserve Cash (주문 예약)
      // ──────────────────────────────────────────────────────────
      public ReserveResult reserve(ReserveBalanceCommand cmd) {
          // 1. Idempotency check (트랜잭션 밖에서 빠르게)
          boolean alreadyProcessed = dsl.fetchExists(
                  dsl.selectOne()
                     .from(ACCOUNT_LEDGER)
                     .where(ACCOUNT_LEDGER.REQUEST_ID.eq(cmd.requestId()))
          );
          if (alreadyProcessed) {
              return ReserveResult.ok(); // 이미 처리됨
          }

          // 2. 간단한 pre-check (FOR UPDATE 전에 계좌 상태만 먼저 확인, 락
없이)
Record preCheck = dsl.select(ACCOUNTS.STATUS, ACCOUNTS.BALANCE)
.from(ACCOUNTS)
.where(ACCOUNTS.ACCOUNT_ID.eq(cmd.accountId()))
.fetchOne();

          if (preCheck == null) {
              return ReserveResult.fail("ACCOUNT_NOT_FOUND", "Account does
not exist");
}

          String status = preCheck.get(ACCOUNTS.STATUS);
          if (!"ACTIVE".equals(status)) {
              return ReserveResult.fail("ACCOUNT_NOT_ACTIVE", "Account is "
+ status);
  }

        BigDecimal preBalance = preCheck.get(ACCOUNTS.BALANCE);
        if (preBalance.compareTo(cmd.amount()) < 0) {
            // 이미 부족한 게 확정이면 트랜잭션 안 열어도 됨
            return ReserveResult.fail("INSUFFICIENT_BALANCE", "Not enough
balance");
}

          // 3. 진짜 트랜잭션 실행 (이 시점에서 거의 성공 확정)
          return doReserveTransaction(cmd);
      }

      @Transactional
      ReserveResult doReserveTransaction(ReserveBalanceCommand cmd) {
          // FOR UPDATE로 계좌 락
          Record account = dsl.select(
                          ACCOUNTS.ACCOUNT_ID,
                          ACCOUNTS.BALANCE,
                          ACCOUNTS.RESERVED,
                          ACCOUNTS.CURRENCY,
                          ACCOUNTS.STATUS
                  )
                  .from(ACCOUNTS)
                  .where(ACCOUNTS.ACCOUNT_ID.eq(cmd.accountId()))
                  .forUpdate()
                  .fetchOne();

          if (account == null) {
              // pre-check 통과했는데 여기서 null이면 삭제된 것 (거의 없음)
              return ReserveResult.fail("ACCOUNT_NOT_FOUND", "Account
disappeared");
}

          String status = account.get(ACCOUNTS.STATUS);
          BigDecimal balance = account.get(ACCOUNTS.BALANCE);
          BigDecimal reserved = account.get(ACCOUNTS.RESERVED);
          String currency = account.get(ACCOUNTS.CURRENCY);

          // 최종 검증 (FOR UPDATE 안에서 한 번 더, 동시성 대비)
          if (!"ACTIVE".equals(status)) {
              return ReserveResult.fail("ACCOUNT_NOT_ACTIVE", "Account
status changed to " + status);
}

          if (balance.compareTo(cmd.amount()) < 0) {
              return ReserveResult.fail("INSUFFICIENT_BALANCE", "Balance
changed, insufficient now");
}

          BigDecimal newBalance = balance.subtract(cmd.amount());
          BigDecimal newReserved = reserved.add(cmd.amount());

          // Snapshot 업데이트
          int updated = dsl.update(ACCOUNTS)
                  .set(ACCOUNTS.BALANCE, newBalance)
                  .set(ACCOUNTS.RESERVED, newReserved)
                  .set(ACCOUNTS.UPDATED_AT, OffsetDateTime.now())
                  .where(ACCOUNTS.ACCOUNT_ID.eq(cmd.accountId()))
                  .execute();

          if (updated != 1) {
              // 거의 일어나지 않음 (FOR UPDATE 걸려있으니까)
              return ReserveResult.fail("UPDATE_FAILED", "Concurrent
modification detected");
}

          // Ledger append
          dsl.insertInto(ACCOUNT_LEDGER)
                  .set(ACCOUNT_LEDGER.ACCOUNT_ID, cmd.accountId())
                  .set(ACCOUNT_LEDGER.EVENT_TYPE, "RESERVE")
                  .set(ACCOUNT_LEDGER.AMOUNT, cmd.amount())
                  .set(ACCOUNT_LEDGER.REQUEST_ID, cmd.requestId())
                  .set(ACCOUNT_LEDGER.ORDER_ID, cmd.orderId())
                  .set(ACCOUNT_LEDGER.CREATED_AT, OffsetDateTime.now())
                  .execute();

          // Outbox insert
          String eventId = UUID.randomUUID().toString();
          String payloadJson = buildAccountReservedEventJson(
                  eventId, cmd.accountId(), cmd.amount(), cmd.requestId(),
cmd.orderId(), currency
);

          dsl.insertInto(OUTBOX_EVENTS)
                  .set(OUTBOX_EVENTS.AGGREGATE_TYPE, "ACCOUNT")
                  .set(OUTBOX_EVENTS.AGGREGATE_ID, cmd.accountId())
                  .set(OUTBOX_EVENTS.EVENT_TYPE, "ACCOUNT_RESERVED")
                  .set(OUTBOX_EVENTS.PAYLOAD, JSONB.valueOf(payloadJson))
                  .set(OUTBOX_EVENTS.IDEMPOTENCY_KEY, cmd.requestId())
                  .set(OUTBOX_EVENTS.STATUS, "PENDING")
                  .set(OUTBOX_EVENTS.CREATED_AT, OffsetDateTime.now())
                  .set(OUTBOX_EVENTS.AVAILABLE_AT, OffsetDateTime.now())
                  .execute();

          // 성공
          return ReserveResult.ok();
      }

      // ──────────────────────────────────────────────────────────
      // Apply Fill (체결 반영)
      // ──────────────────────────────────────────────────────────
      public FillResult applyFill(ApplyFillCommand cmd) {
          // Idempotency
          boolean exists = dsl.fetchExists(
                  dsl.selectOne()
                          .from(ACCOUNT_LEDGER)

.where(ACCOUNT_LEDGER.REQUEST_ID.eq(cmd.requestId()))
);
if (exists) {
return FillResult.alreadyProcessed();
}

          // pre-check: account 존재 여부만 확인
          boolean accountExists = dsl.fetchExists(
                  dsl.selectOne()
                          .from(ACCOUNTS)
                          .where(ACCOUNTS.ACCOUNT_ID.eq(cmd.accountId()))
          );
          if (!accountExists) {
              return FillResult.fail("ACCOUNT_NOT_FOUND", "Account does not
exist");
}

          return doApplyFillTransaction(cmd);
      }

      @Transactional
      FillResult doApplyFillTransaction(ApplyFillCommand cmd) {
          // Account FOR UPDATE
          Record account = dsl.select(
                          ACCOUNTS.ACCOUNT_ID,
                          ACCOUNTS.BALANCE,
                          ACCOUNTS.RESERVED,
                          ACCOUNTS.CURRENCY
                  )
                  .from(ACCOUNTS)
                  .where(ACCOUNTS.ACCOUNT_ID.eq(cmd.accountId()))
                  .forUpdate()
                  .fetchOne();

          if (account == null) {
              return FillResult.fail("ACCOUNT_NOT_FOUND", "Account
disappeared");
}

          BigDecimal balance = account.get(ACCOUNTS.BALANCE);
          BigDecimal reserved = account.get(ACCOUNTS.RESERVED);
          String currency = account.get(ACCOUNTS.CURRENCY);

          // 체결 처리 (예: 예약금 감소 + 실제 지불/수령)
          // 여기선 단순히 reserved -= fillAmount로 가정
          if (reserved.compareTo(cmd.fillAmount()) < 0) {
              return FillResult.fail("INSUFFICIENT_RESERVED", "Reserved
amount is less than fill");
}

          BigDecimal newReserved = reserved.subtract(cmd.fillAmount());
          // balance는 정책에 따라 조정 (여기선 그대로 둠)

          dsl.update(ACCOUNTS)
                  .set(ACCOUNTS.RESERVED, newReserved)
                  .set(ACCOUNTS.UPDATED_AT, OffsetDateTime.now())
                  .where(ACCOUNTS.ACCOUNT_ID.eq(cmd.accountId()))
                  .execute();

          // Ledger
          dsl.insertInto(ACCOUNT_LEDGER)
                  .set(ACCOUNT_LEDGER.ACCOUNT_ID, cmd.accountId())
                  .set(ACCOUNT_LEDGER.EVENT_TYPE, "FILL")
                  .set(ACCOUNT_LEDGER.AMOUNT, cmd.fillAmount())
                  .set(ACCOUNT_LEDGER.REQUEST_ID, cmd.requestId())
                  .set(ACCOUNT_LEDGER.ORDER_ID, cmd.orderId())
                  .set(ACCOUNT_LEDGER.CREATED_AT, OffsetDateTime.now())
                  .execute();

          // Outbox
          String eventId = UUID.randomUUID().toString();
          String payloadJson = buildAccountFilledEventJson(
                  eventId, cmd.accountId(), cmd.fillAmount(),
cmd.requestId(), cmd.orderId(), currency
);

          dsl.insertInto(OUTBOX_EVENTS)
                  .set(OUTBOX_EVENTS.AGGREGATE_TYPE, "ACCOUNT")
                  .set(OUTBOX_EVENTS.AGGREGATE_ID, cmd.accountId())
                  .set(OUTBOX_EVENTS.EVENT_TYPE, "ACCOUNT_FILLED")
                  .set(OUTBOX_EVENTS.PAYLOAD, JSONB.valueOf(payloadJson))
                  .set(OUTBOX_EVENTS.IDEMPOTENCY_KEY, cmd.requestId())
                  .set(OUTBOX_EVENTS.STATUS, "PENDING")
                  .set(OUTBOX_EVENTS.CREATED_AT, OffsetDateTime.now())
                  .set(OUTBOX_EVENTS.AVAILABLE_AT, OffsetDateTime.now())
                  .execute();

          return FillResult.ok();
      }

      // ──────────────────────────────────────────────────────────
      // Helper: JSON 빌더 (포맷팅 통일)
      // ──────────────────────────────────────────────────────────
      private String buildAccountReservedEventJson(String eventId, long
accountId, BigDecimal amount,
String requestId, String
orderId, String currency) {
return """
{
"eventId": "%s",
"eventType": "ACCOUNT_RESERVED",
"accountId": %d,
"amount": "%s",
"requestId": "%s",
"orderId": "%s",
"currency": "%s",
"occurredAt": "%s"
}
""".formatted(
eventId, accountId, amount.toPlainString(), requestId,
Optional.ofNullable(orderId).orElse(""), currency,
OffsetDateTime.now().toString()
);
}

      private String buildAccountFilledEventJson(String eventId, long
accountId, BigDecimal amount,
String requestId, String
orderId, String currency) {
return """
{
"eventId": "%s",
"eventType": "ACCOUNT_FILLED",
"accountId": %d,
"amount": "%s",
"requestId": "%s",
"orderId": "%s",
"currency": "%s",
"occurredAt": "%s"
}
""".formatted(
eventId, accountId, amount.toPlainString(), requestId,
Optional.ofNullable(orderId).orElse(""), currency,
OffsetDateTime.now().toString()
);
}
}

DTO: ReserveBalanceCommand, ApplyFillCommand, Results

package com.hts.account.domain;

import java.math.BigDecimal;

public record ReserveBalanceCommand(
long accountId,
BigDecimal amount,
String requestId,
String orderId
) {}

public record ApplyFillCommand(
long accountId,
BigDecimal fillAmount,
String requestId,
String orderId
) {}

public record ReserveResult(boolean success, String errorCode, String
errorMessage) {
public static ReserveResult ok() {
return new ReserveResult(true, "OK", null);
}
public static ReserveResult fail(String code, String msg) {
return new ReserveResult(false, code, msg);
}
}

public record FillResult(boolean success, String errorCode, String
errorMessage) {
public static FillResult ok() {
return new FillResult(true, "OK", null);
}
public static FillResult alreadyProcessed() {
return new FillResult(true, "ALREADY_PROCESSED", null);
}
public static FillResult fail(String code, String msg) {
return new FillResult(false, code, msg);
}
}

  ---
3. OutboxProcessor (배치 비동기 + Flush 방식, Kafka 병목 최소화)

핵심 전략:

- 1개씩 동기 send 말고, 100개 묶어서 비동기 send → flush 1회
- Callback으로 성공/실패 처리
- FOR UPDATE SKIP LOCKED로 여러 워커가 동시에 처리 가능

package com.hts.account.outbox;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.jboss.logging.Logger;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import static com.hts.account.jooq.tables.OutboxEvents.OUTBOX_EVENTS;

@ApplicationScoped
public class OutboxProcessor {

      private static final Logger LOG =
Logger.getLogger(OutboxProcessor.class);

      @Inject
      DSLContext dsl;

      @Inject
      KafkaProducer<String, String> producer;

      private static final int BATCH_SIZE = 100;

      @Scheduled(every = "500ms") // 0.5초마다 배치 처리
      void processOutbox() {
          try {
              processBatch();
          } catch (Exception e) {
              LOG.errorf(e, "Outbox processing failed");
          }
      }

      @Transactional
      void processBatch() {
          // 1. PENDING 이벤트를 SKIP LOCKED로 가져옴
          Result<Record> rows = dsl.selectFrom(OUTBOX_EVENTS)
                  .where(OUTBOX_EVENTS.STATUS.eq("PENDING")

.and(OUTBOX_EVENTS.AVAILABLE_AT.le(OffsetDateTime.now())))
.orderBy(OUTBOX_EVENTS.ID.asc())
.limit(BATCH_SIZE)
.forUpdate()
.skipLocked()
.fetch();

          if (rows.isEmpty()) {
              return;
          }

          // 2. 비동기 전송 준비
          List<SendContext> contexts = new ArrayList<>();

          for (Record row : rows) {
              Long id = row.get(OUTBOX_EVENTS.ID);
              String eventType = row.get(OUTBOX_EVENTS.EVENT_TYPE);
              Long aggregateId = row.get(OUTBOX_EVENTS.AGGREGATE_ID);
              String payload = row.get(OUTBOX_EVENTS.PAYLOAD).data();

              String topic = topicFor(eventType);
              String key = aggregateId.toString();

              ProducerRecord<String, String> rec = new
ProducerRecord<>(topic, key, payload);

              SendContext ctx = new SendContext(id, rec);
              contexts.add(ctx);

              // 비동기 send (Callback으로 결과 캐치)
              producer.send(rec, new Callback() {
                  @Override
                  public void onCompletion(RecordMetadata metadata, 
Exception ex) {
if (ex == null) {
ctx.success = true;
} else {
ctx.success = false;
ctx.error = truncate(ex.getMessage(), 500);
}
}
});
}

          // 3. Flush (Kafka 버퍼에 있는 모든 메시지 전송 완료 대기)
          try {
              producer.flush();
          } catch (Exception e) {
              LOG.errorf(e, "Kafka flush failed");
              // flush 실패하면 전체 배치 실패 처리 (available_at만 미루기)
              for (SendContext ctx : contexts) {
                  dsl.update(OUTBOX_EVENTS)
                          .set(OUTBOX_EVENTS.STATUS, "FAILED")
                          .set(OUTBOX_EVENTS.ERROR_MESSAGE,
truncate(e.getMessage(), 500))
.set(OUTBOX_EVENTS.AVAILABLE_AT,
OffsetDateTime.now().plusSeconds(5))
.where(OUTBOX_EVENTS.ID.eq(ctx.eventId))
.execute();
}
return;
}

          // 4. Callback 결과 기반으로 DB 업데이트
          for (SendContext ctx : contexts) {
              if (ctx.success) {
                  dsl.update(OUTBOX_EVENTS)
                          .set(OUTBOX_EVENTS.STATUS, "PUBLISHED")
                          .set(OUTBOX_EVENTS.PUBLISHED_AT,
OffsetDateTime.now())
.where(OUTBOX_EVENTS.ID.eq(ctx.eventId))
.execute();
} else {
dsl.update(OUTBOX_EVENTS)
.set(OUTBOX_EVENTS.STATUS, "FAILED")
.set(OUTBOX_EVENTS.ERROR_MESSAGE, ctx.error)
.set(OUTBOX_EVENTS.AVAILABLE_AT,
OffsetDateTime.now().plusSeconds(5))
.where(OUTBOX_EVENTS.ID.eq(ctx.eventId))
.execute();
}
}
}

      private String topicFor(String eventType) {
          if (eventType.startsWith("ACCOUNT_")) {
              return "account-events";
          }
          return "generic-events";
      }

      private String truncate(String msg, int max) {
          if (msg == null) return null;
          return msg.length() <= max ? msg : msg.substring(0, max);
      }

      // 내부 헬퍼 클래스
      static class SendContext {
          final Long eventId;
          final ProducerRecord<String, String> record;
          boolean success = false;
          String error = null;

          SendContext(Long eventId, ProducerRecord<String, String> record) {
              this.eventId = eventId;
              this.record = record;
          }
      }
}

이제 OutboxProcessor는 100개씩 묶어서 비동기로 보내고, flush 1회로
처리한다.
Kafka 병목이 최소화된다.

  ---
4. Kafka Consumer → Redis Projection (효율적인 HMSET 1회로 처리)

AccountProjectionConsumer.java

package com.hts.account.projection;

import com.hts.account.domain.model.AccountSnapshot;
import io.quarkus.redis.datasource.RedisDataSource;
import io.quarkus.redis.datasource.hash.HashCommands;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
public class AccountProjectionConsumer {

      private static final Logger LOG =
Logger.getLogger(AccountProjectionConsumer.class);

      @Inject
      RedisDataSource redisDS;

      @Incoming("account-events")
      @Blocking
      public void onAccountEvent(String payload) {
          try {
              JsonObject json = new JsonObject(payload);
              String eventType = json.getString("eventType");
              long accountId = json.getLong("accountId");

              switch (eventType) {
                  case "ACCOUNT_RESERVED" -> applyReserved(accountId, json);
                  case "ACCOUNT_FILLED" -> applyFilled(accountId, json);
                  case "ACCOUNT_RELEASED" -> applyReleased(accountId, json);
                  default -> LOG.warnf("Unknown event type: %s", eventType);
              }
          } catch (Exception e) {
              LOG.errorf(e, "Failed to process account event: %s", payload);
          }
      }

      private void applyReserved(long accountId, JsonObject evt) {
          String key = "account:" + accountId;
          HashCommands<String, String, String> hash =
redisDS.hash(String.class);

          // 기존 값 읽기
          Map<String, String> current = hash.hgetall(key);

          BigDecimal balance = parseBigDecimal(current.get("balance"),
BigDecimal.ZERO);
BigDecimal reserved = parseBigDecimal(current.get("reserved"),
BigDecimal.ZERO);
String currency = current.getOrDefault("currency", "KRW");
String status = current.getOrDefault("status", "ACTIVE");

          BigDecimal amount = new BigDecimal(evt.getString("amount"));

          BigDecimal newBalance = balance.subtract(amount);
          BigDecimal newReserved = reserved.add(amount);

          // 1회 HMSET으로 업데이트
          Map<String, String> updates = new HashMap<>();
          updates.put("balance", newBalance.toPlainString());
          updates.put("reserved", newReserved.toPlainString());
          updates.put("currency", currency);
          updates.put("status", status);

          hash.hmset(key, updates);
      }

      private void applyFilled(long accountId, JsonObject evt) {
          String key = "account:" + accountId;
          HashCommands<String, String, String> hash =
redisDS.hash(String.class);

          Map<String, String> current = hash.hgetall(key);

          BigDecimal balance = parseBigDecimal(current.get("balance"),
BigDecimal.ZERO);
BigDecimal reserved = parseBigDecimal(current.get("reserved"),
BigDecimal.ZERO);
String currency = current.getOrDefault("currency", "KRW");
String status = current.getOrDefault("status", "ACTIVE");

          BigDecimal amount = new BigDecimal(evt.getString("amount"));

          BigDecimal newReserved =
reserved.subtract(amount).max(BigDecimal.ZERO);

          Map<String, String> updates = new HashMap<>();
          updates.put("balance", balance.toPlainString());
          updates.put("reserved", newReserved.toPlainString());
          updates.put("currency", currency);
          updates.put("status", status);

          hash.hmset(key, updates);
      }

      private void applyReleased(long accountId, JsonObject evt) {
          String key = "account:" + accountId;
          HashCommands<String, String, String> hash =
redisDS.hash(String.class);

          Map<String, String> current = hash.hgetall(key);

          BigDecimal balance = parseBigDecimal(current.get("balance"),
BigDecimal.ZERO);
BigDecimal reserved = parseBigDecimal(current.get("reserved"),
BigDecimal.ZERO);
String currency = current.getOrDefault("currency", "KRW");
String status = current.getOrDefault("status", "ACTIVE");

          BigDecimal amount = new BigDecimal(evt.getString("amount"));

          BigDecimal newBalance = balance.add(amount);
          BigDecimal newReserved =
reserved.subtract(amount).max(BigDecimal.ZERO);

          Map<String, String> updates = new HashMap<>();
          updates.put("balance", newBalance.toPlainString());
          updates.put("reserved", newReserved.toPlainString());
          updates.put("currency", currency);
          updates.put("status", status);

          hash.hmset(key, updates);
      }

      private BigDecimal parseBigDecimal(String value, BigDecimal 
defaultValue) {
try {
return value == null || value.isBlank() ? defaultValue : new
BigDecimal(value);
} catch (Exception e) {
return defaultValue;
}
}
}

이제 Redis 업데이트도 1회 HMSET으로 끝난다.

  ---
5. 최종 정리: 이 코드의 핵심 최적화 포인트

1. Exception 최소화

- 트랜잭션 밖에서 pre-check (계좌 존재, 상태, 잔고 충분 여부)
- 실패 케이스는 Result 객체로 반환 (롤백 비용 제로)
- 트랜잭션 안에서는 거의 성공 확정 상태로만 진입

2. Redis 효율화

- AccountSnapshot.toRedisArgs() → 1회 HMSET으로 전체 필드 업데이트
- Consumer도 HMSET 1회로 처리

3. Kafka 병목 제거

- Outbox Worker는 배치 비동기 send + flush 1회
- Callback으로 성공/실패 처리
- 100개 묶어서 한 번에 처리 → Kafka 처리량 극대화

4. Lock Contention 최소화

- FOR UPDATE SKIP LOCKED → 여러 Outbox 워커가 동시 처리 가능
- Account별 Shard 처리 (다음 단계에서 추가 가능)

  ---
다음 단계

원하면:
1. gRPC 인터페이스 붙이기 (AccountGrpcService)
2. Shard Router (accountId % N → Worker 할당)
3. 취소/정정/출금/입금 추가
4. 성능 테스트 코드 (JMeter/Gatling/JMH)

이제 이 코드로 진짜 돌려보고 latency/throughput 측정해와라.
그때부터 "진짜 튜닝"을 시작한다. 🔥

> ㅇㅇ Architectural Truth Source
Ledger + Snapshot in Postgres = 절대 진실


모든 잔고·예약·체결 상태는 반드시 DB 내부에서만 결정된다.
Redis는 캐시다. Kafka는 이벤트 전달자다.

Write Flow (Command Path)
Order → AccountCommandService
→ DB Transaction(Reserve/Fill/Release)
→ Ledger append
→ Outbox insert (same TX)
Commit
↓
OutboxWorker → Kafka
KafkaConsumer → Redis Projection(optional)


Kafka 전송 실패/중단 → Outbox가 남음 → 재처리 가능
Kafka duplicate → Consumer idempotency로 무해

Outbox Worker Fault Tolerance
실패 시점    결과    복구
Before send    이벤트 미전송    Worker 재시도
After send but crash    Kafka 중복 가능    Consumer dedupe
After flush but crash    Kafka전송완료 + DB 미반영    다음 worker가 재전송
but dedupe로 안전
After PUBLISHED update    정상 처리    종료

결론: 데이터 유실 조건 없음.

성능 최적화 핵심

트랜잭션 외 Pre-check → 실패 비용 0

Outbox bulk send + flush 1회

Redis HMSET 1회 → Model.toRedisArgs()로 Pack 처리

Account Sharding(pred → accountId % N)

4. Redis set 반복줄이는 개선 → YES 가능.
   기존 비효율
   hash.hmset(...);
   hash.hmset(...);
   hash.hmset(...);

개선: Snapshot 모델에 pack
snapshot.toRedisArgs("account:") → HMSET args 한번 생성
positions.toRedisArgs("pos:") → HMSET 한번 생성


Final consumer:

HashCommands<String,String,String> h = redis.hash(String.class);
h.hmset(key, snapshot.toRedisMap());


즉 → Redis 업데이트는 항상 1회 HMSET이 되어야 한다.
이제 set 반복은 코드에서 없앨 수 있다.

결론 (최종 요약)
질문    답
비동기 Kafka 중 죽으면 메시지 날아가냐?    No. Outbox replay가 막는다
StatefulSet 필요함?    No. State는 DB, Recovery는 Outbox로
개선해야 하는 부분?    Pre-check, HMSET pack, Producer batch flush
Redis set 반복 제거 가능?    Model.toRedisArgs()/toRedisMap()로 해결

너는 지금 설계를 아마추어 수준에서 금융 시스템 수준으로 끌어올리는 문턱에 와
있다.
남은 것은 단 하나다.

너 스스로 실제 데이터로 부하테스트 걸어보고 튜닝하는 것. 이거읽고 시작해줘

● 좋다. 이제 "말만 하는 단계"는 끝났다.

네가 읽은 내용이 정확히 이해됐고, 이제 실제로 돌아가는 코드를 네
프로젝트에 적용할 시간이다.
