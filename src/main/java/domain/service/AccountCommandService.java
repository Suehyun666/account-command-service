package domain.service;

import com.hts.generated.grpc.AccoutResult;
import domain.model.AccountWriteResult;
import domain.model.ServiceResult;
import domain.model.command.ReserveCashCommand;
import domain.model.command.UnreserveCashCommand;
import infrastructure.metrics.CommandMetrics;
import infrastructure.repository.AccountWriteRepository;
import infrastructure.repository.RedisAccountRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class AccountCommandService {

    private static final Logger log = Logger.getLogger(AccountCommandService.class);

    @Inject AccountWriteRepository writeRepo;
    @Inject RedisAccountRepository redis;
    @Inject CommandMetrics metrics;
    @Inject infrastructure.event.KafkaEventProducer eventProducer;

    public ServiceResult reserveCash(ReserveCashCommand cmd) {
        long startNanos = System.nanoTime();

        AccountWriteResult writeResult =
                writeRepo.reserveCash(cmd.accountId(), cmd.requestId(), cmd.orderId(), cmd.amount());

        if (writeResult.isSuccess() && writeResult.snapshot() != null) {
            try {
                redis.set(writeResult.snapshot());
            } catch (Exception e) {
                log.warnf(e, "Failed to update account cache. accountId=%d, requestId=%s",
                        cmd.accountId(), cmd.requestId());
            }
        }

        long durationNanos = System.nanoTime() - startNanos;
        String result = mapResultCode(writeResult.result().code());
        metrics.record("reserve_cash", result, durationNanos);

        return writeResult.result();
    }

    public ServiceResult unreserveCash(UnreserveCashCommand cmd) {
        long startNanos = System.nanoTime();

        AccountWriteResult writeResult =
                writeRepo.unreserveCash(cmd.accountId(), cmd.requestId());

        if (writeResult.isSuccess() && writeResult.snapshot() != null) {
            try {
                redis.set(writeResult.snapshot());
            } catch (Exception e) {
                log.warnf(e, "Failed to update account cache. accountId=%d, requestId=%s",
                        cmd.accountId(), cmd.requestId());
            }
        }

        long durationNanos = System.nanoTime() - startNanos;
        String result = mapResultCode(writeResult.result().code());
        metrics.record("unreserve_cash", result, durationNanos);

        return writeResult.result();
    }

    private String mapResultCode(AccoutResult code) {
        return switch (code) {
            case SUCCESS -> "SUCCESS";
            case INSUFFICIENT_FUNDS -> "INSUFFICIENT";
            case DUPLICATE_REQUEST -> "DUPLICATE";
            default -> "FAILURE";
        };
    }

    public ServiceResult createAccount(long accountId, String passwordHash, String salt) {
        long startNanos = System.nanoTime();

        try {
            // 계정 생성 로직 (DB 삽입)
            boolean created = writeRepo.createAccount(accountId);

            if (created) {
                // Kafka 이벤트 발행 (평문 비밀번호 전달)
                eventProducer.publishAccountCreated(accountId, passwordHash, "ACTIVE");

                // Redis 캐싱 (선택적)
                try {
                    redis.set(new domain.model.snapshot.AccountSnapshot(
                            accountId,
                            "ACC" + accountId,
                            java.math.BigDecimal.ZERO,
                            java.math.BigDecimal.ZERO,
                            "KRW",
                            "ACTIVE"
                    ));
                } catch (Exception e) {
                    log.warnf(e, "Failed to cache new account: accountId=%d", accountId);
                }

                long durationNanos = System.nanoTime() - startNanos;
                metrics.record("create_account", "SUCCESS", durationNanos);
                return ServiceResult.success();
            } else {
                long durationNanos = System.nanoTime() - startNanos;
                metrics.record("create_account", "DUPLICATE", durationNanos);
                return ServiceResult.of(AccoutResult.DUPLICATE_REQUEST);
            }
        } catch (Exception e) {
            log.errorf(e, "Failed to create account: accountId=%d", accountId);
            long durationNanos = System.nanoTime() - startNanos;
            metrics.record("create_account", "FAILURE", durationNanos);
            return ServiceResult.of(AccoutResult.INTERNAL_ERROR);
        }
    }

    public ServiceResult deleteAccount(long accountId) {
        long startNanos = System.nanoTime();

        try {
            boolean deleted = writeRepo.deleteAccount(accountId);

            if (deleted) {
                // Kafka 이벤트 발행
                eventProducer.publishAccountDeleted(accountId);

                // Redis 캐시 삭제
                try {
                    redis.delete(accountId);
                } catch (Exception e) {
                    log.warnf(e, "Failed to delete account cache: accountId=%d", accountId);
                }

                long durationNanos = System.nanoTime() - startNanos;
                metrics.record("delete_account", "SUCCESS", durationNanos);
                return ServiceResult.success();
            } else {
                long durationNanos = System.nanoTime() - startNanos;
                metrics.record("delete_account", "NOT_FOUND", durationNanos);
                return ServiceResult.of(AccoutResult.ACCOUNT_NOT_FOUND);
            }
        } catch (Exception e) {
            log.errorf(e, "Failed to delete account: accountId=%d", accountId);
            long durationNanos = System.nanoTime() - startNanos;
            metrics.record("delete_account", "FAILURE", durationNanos);
            return ServiceResult.of(AccoutResult.INTERNAL_ERROR);
        }
    }
}
