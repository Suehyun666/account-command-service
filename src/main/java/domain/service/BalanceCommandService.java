package domain.service;

import domain.model.command.DepositCommand;
import domain.model.command.ReserveCashCommand;
import domain.model.command.ReleaseCashCommand;
import domain.model.command.WithdrawCommand;
import domain.model.result.CommandResult;
import infrastructure.metrics.CommandMetrics;
import infrastructure.repository.BalanceWriteRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class BalanceCommandService {

    @Inject BalanceWriteRepository writeRepo;
    @Inject CommandMetrics metrics;

    public CommandResult reserveCash(ReserveCashCommand cmd) {
        long startNanos = System.nanoTime();

        CommandResult result = writeRepo.reserveCash(
                cmd.accountId(), cmd.requestId(), cmd.orderId(), cmd.amount()
        );

        long durationNanos = System.nanoTime() - startNanos;
        String metricResult = result.success() ? "SUCCESS" : result.errorCode();
        metrics.record("reserve_cash", metricResult, durationNanos);

        return result;
    }

    public CommandResult releaseCash(ReleaseCashCommand cmd) {
        long startNanos = System.nanoTime();

        CommandResult result = writeRepo.unreserveCash(cmd.accountId(), cmd.requestId());

        long durationNanos = System.nanoTime() - startNanos;
        String metricResult = result.success() ? "SUCCESS" : result.errorCode();
        metrics.record("unreserve_cash", metricResult, durationNanos);

        return result;
    }

    public CommandResult deposit(DepositCommand cmd) {
        long startNanos = System.nanoTime();

        CommandResult result = writeRepo.deposit(cmd.accountId(), cmd.amount(), cmd.source());

        long durationNanos = System.nanoTime() - startNanos;
        String metricResult = result.success() ? "SUCCESS" : result.errorCode();
        metrics.record("deposit", metricResult, durationNanos);

        return result;
    }

    public CommandResult withdraw(WithdrawCommand cmd) {
        long startNanos = System.nanoTime();

        CommandResult result = writeRepo.withdraw(cmd.accountId(), cmd.amount(), cmd.destination());

        long durationNanos = System.nanoTime() - startNanos;
        String metricResult = result.success() ? "SUCCESS" : result.errorCode();
        metrics.record("withdraw", metricResult, durationNanos);

        return result;
    }
}
