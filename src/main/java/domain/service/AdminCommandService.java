package domain.service;

import infrastructure.event.KafkaEventProducer;
import infrastructure.metrics.CommandMetrics;
import infrastructure.repository.AccountWriteRepository;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

public class AdminCommandService {

    private static final Logger log = Logger.getLogger(BalanceCommandService.class);

    @Inject AccountWriteRepository writeRepo;
    @Inject CommandMetrics metrics;
    @Inject KafkaEventProducer eventProducer;

    public Uni<Boolean> createAccount(long accountId, String passwordHash, String salt) {
        long startNanos = System.nanoTime();

        return Uni.createFrom().item(() -> {
            try {
                boolean created = writeRepo.createAccount(accountId);

                if (created) {
                    eventProducer.publishAccountCreated(accountId, passwordHash, "ACTIVE");
                    long durationNanos = System.nanoTime() - startNanos;
                    metrics.record("create_account", "SUCCESS", durationNanos);
                    return true;
                } else {
                    long durationNanos = System.nanoTime() - startNanos;
                    metrics.record("create_account", "DUPLICATE", durationNanos);
                    return false;
                }
            } catch (Exception e) {
                log.errorf(e, "Failed to create account: accountId=%d", accountId);
                long durationNanos = System.nanoTime() - startNanos;
                metrics.record("create_account", "FAILURE", durationNanos);
                return false;
            }
        });
    }

    public Uni<Boolean> deleteAccount(long accountId) {
        long startNanos = System.nanoTime();

        return Uni.createFrom().item(() -> {
            try {
                boolean deleted = writeRepo.deleteAccount(accountId);

                if (deleted) {
                    eventProducer.publishAccountDeleted(accountId);
                    long durationNanos = System.nanoTime() - startNanos;
                    metrics.record("delete_account", "SUCCESS", durationNanos);
                    return true;
                } else {
                    long durationNanos = System.nanoTime() - startNanos;
                    metrics.record("delete_account", "NOT_FOUND", durationNanos);
                    return false;
                }
            } catch (Exception e) {
                log.errorf(e, "Failed to delete account: accountId=%d", accountId);
                long durationNanos = System.nanoTime() - startNanos;
                metrics.record("delete_account", "FAILURE", durationNanos);
                return false;
            }
        });
    }
}
