package domain.service;

import domain.model.command.ReservePositionCommand;
import domain.model.command.ReleasePositionCommand;
import domain.model.result.CommandResult;
import infrastructure.metrics.CommandMetrics;
import infrastructure.repository.PositionWriteRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

@ApplicationScoped
public class PositionCommandService {

    private static final Logger log = Logger.getLogger(PositionCommandService.class);

    @Inject PositionWriteRepository writeRepo;
    @Inject CommandMetrics metrics;

    public CommandResult reservePosition(ReservePositionCommand cmd) {
        long startNanos = System.nanoTime();

        CommandResult result = writeRepo.reservePosition(
                cmd.accountId(), cmd.symbol(), cmd.quantity(), cmd.requestId()
        );

        long durationNanos = System.nanoTime() - startNanos;
        String metricResult = result.success() ? "SUCCESS" : result.errorCode();
        metrics.record("reserve_position", metricResult, durationNanos);

        return result;
    }

    public CommandResult releasePosition(ReleasePositionCommand cmd) {
        long startNanos = System.nanoTime();

        CommandResult result = writeRepo.unreservePosition(cmd.accountId(), cmd.requestId());

        long durationNanos = System.nanoTime() - startNanos;
        String metricResult = result.success() ? "SUCCESS" : result.errorCode();
        metrics.record("unreserve_position", metricResult, durationNanos);

        return result;
    }
}
