package api;

import com.hts.generated.grpc.account.order.*;
import com.hts.generated.grpc.CommonReply;
import domain.model.command.ReserveCashCommand;
import domain.model.command.ReservePositionCommand;
import domain.model.command.ReleaseCashCommand;
import domain.model.command.ReleasePositionCommand;
import domain.model.result.CommandResult;
import domain.service.BalanceCommandService;
import domain.service.PositionCommandService;
import infrastructure.shard.AccountShardInvoker;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;

import java.math.BigDecimal;

@GrpcService
public class AccountOrderGrpcService implements AccountOrderService {

    @Inject BalanceCommandService balanceCommandService;
    @Inject PositionCommandService positionCommandService;
    @Inject AccountShardInvoker invoker;

    @Override
    public Uni<CommonReply> reserveCash(ReserveCashRequest request) {
        long accountId = request.getAccountId();
        long amountMicroUnits = request.getAmountMicroUnits();
        String reserveId = request.getReserveId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.accountNotFound()));
        }
        if (amountMicroUnits <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_AMOUNT", "Invalid amount")));
        }
        if (reserveId == null || reserveId.isBlank()) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid reserve ID")));
        }

        BigDecimal amount = BigDecimal.valueOf(amountMicroUnits).divide(BigDecimal.valueOf(1_000_000));
        ReserveCashCommand cmd = new ReserveCashCommand(accountId, amount, reserveId, request.getOrderId());
        return invoker.invoke(accountId, () -> balanceCommandService.reserveCash(cmd))
                .onItem().transform(this::toReply);
    }

    @Override
    public Uni<CommonReply> releaseCash(ReleaseCashRequest request) {
        long accountId = request.getAccountId();
        String reserveId = request.getReserveId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.accountNotFound()));
        }
        if (reserveId == null || reserveId.isBlank()) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid reserve ID")));
        }

        ReleaseCashCommand cmd = new ReleaseCashCommand(accountId, reserveId);
        return invoker.invoke(accountId, () -> balanceCommandService.releaseCash(cmd))
                .onItem().transform(this::toReply);
    }

    @Override
    public Uni<CommonReply> reservePosition(ReservePositionRequest request) {
        long accountId = request.getAccountId();
        String symbol = request.getSymbol();
        long quantity = request.getQuantity();
        String reserveId = request.getReserveId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.accountNotFound()));
        }
        if (symbol == null || symbol.isBlank()) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid symbol")));
        }
        if (quantity <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_AMOUNT", "Invalid quantity")));
        }
        if (reserveId == null || reserveId.isBlank()) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid reserve ID")));
        }

        ReservePositionCommand cmd = new ReservePositionCommand(
                accountId, symbol, BigDecimal.valueOf(quantity), reserveId, request.getOrderId()
        );
        return invoker.invoke(accountId, () -> positionCommandService.reservePosition(cmd))
                .onItem().transform(this::toReply);
    }

    @Override
    public Uni<CommonReply> releasePosition(ReleasePositionRequest request) {
        long accountId = request.getAccountId();
        String reserveId = request.getReserveId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.accountNotFound()));
        }
        if (reserveId == null || reserveId.isBlank()) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid reserve ID")));
        }

        ReleasePositionCommand cmd = new ReleasePositionCommand(accountId, reserveId);
        return invoker.invoke(accountId, () -> positionCommandService.releasePosition(cmd))
                .onItem().transform(this::toReply);
    }

    private CommonReply toReply(CommandResult result) {
        return CommonReply.newBuilder().setCode(result.toGrpcCode()).build();
    }
}
