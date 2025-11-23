package api;

import com.hts.generated.grpc.*;
import domain.model.*;
import domain.model.command.ReserveCashCommand;
import domain.model.command.ReservePositionCommand;
import domain.model.command.UnreserveCashCommand;
import domain.model.command.UnreservePositionCommand;
import domain.service.AccountCommandService;
import domain.service.PositionCommandService;
import infrastructure.shard.AccountShardInvoker;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import util.MoneyParser;

import java.math.BigDecimal;

@GrpcService
public class AccountOrderGrpcService implements AccountOrderService {

    @Inject AccountCommandService accountCommandService;
    @Inject PositionCommandService positionCommandService;
    @Inject AccountShardInvoker invoker;

    @Override
    public Uni<CommonReply> reserveCash(ReserveCashRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.ACCOUNT_NOT_FOUND)));
        }

        BigDecimal amount = MoneyParser.parseSafe(request.getAmount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.INVALID_AMOUNT)));
        }

        String reserveId = request.getReserveId();
        if (reserveId == null || reserveId.isBlank()) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.INVALID_REQUEST)));
        }

        ReserveCashCommand cmd = new ReserveCashCommand(
                accountId,
                amount,
                reserveId,
                request.getOrderId()
        );

        return invoker.invoke(accountId,
                () -> accountCommandService.reserveCash(cmd)
        ).onItem().transform(this::toReply);
    }

    @Override
    public Uni<CommonReply> cancelCashReserve(CancelCashReserveRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.ACCOUNT_NOT_FOUND)));
        }

        String reserveId = request.getReserveId();
        if (reserveId == null || reserveId.isBlank()) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.INVALID_REQUEST)));
        }

        UnreserveCashCommand cmd = new UnreserveCashCommand(accountId, reserveId);

        return invoker.invoke(accountId,
                () -> accountCommandService.unreserveCash(cmd)
        ).onItem().transform(this::toReply);
    }

    @Override
    public Uni<CommonReply> reservePosition(ReservePositionRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.ACCOUNT_NOT_FOUND)));
        }

        String symbol = request.getSymbol();
        if (symbol == null || symbol.isBlank()) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.INVALID_REQUEST)));
        }

        long qty = request.getQuantity();
        if (qty <= 0) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.INVALID_AMOUNT)));
        }

        ReservePositionCommand cmd = new ReservePositionCommand(
                accountId,
                symbol,
                BigDecimal.valueOf(qty),
                request.getReserveId(),
                request.getOrderId()
        );

        return invoker.invoke(accountId,
                () -> positionCommandService.reservePosition(cmd)
        ).onItem().transform(this::toReply);
    }

    @Override
    public Uni<CommonReply> cancelPositionReserve(CancelPositionReserveRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.ACCOUNT_NOT_FOUND)));
        }

        String reserveId = request.getReserveId();
        if (reserveId == null || reserveId.isBlank()) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.INVALID_REQUEST)));
        }

        UnreservePositionCommand cmd = new UnreservePositionCommand(accountId, reserveId);

        return invoker.invoke(accountId,
                () -> positionCommandService.unreservePosition(cmd)
        ).onItem().transform(this::toReply);
    }

    private CommonReply toReply(ServiceResult result) {
        return CommonReply.newBuilder()
                .setCode(result.code())
                .build();
    }
}
