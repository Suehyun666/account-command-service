package api;

import com.hts.generated.grpc.*;
import domain.model.command.DepositCommand;
import domain.model.command.WithdrawCommand;
import domain.model.result.CommandResult;
import domain.service.AdminCommandService;
import domain.service.BalanceCommandService;
import infrastructure.shard.AccountShardInvoker;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;

import java.math.BigDecimal;

@GrpcService
public class AccountAdminGrpcService implements AccountAdminService {

    @Inject BalanceCommandService balanceCommandService;
    @Inject AdminCommandService adminCommandService;
    @Inject AccountShardInvoker invoker;

    @Override
    public Uni<CommonReply> createAccount(CreateAccountRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid account ID")));
        }

        String password = request.getPassword();
        if (password == null || password.isBlank()) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid password")));
        }

        return invoker.invoke(accountId,
                () -> adminCommandService.createAccount(accountId, password, "")
        ).onItem().transformToUni(uni -> uni)
         .onItem().transform(success -> toReply(success ? CommandResult.ok() : CommandResult.fail("INTERNAL_ERROR", "Failed to create account")));
    }

    @Override
    public Uni<CommonReply> deleteAccount(DeleteAccountRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid account ID")));
        }

        return invoker.invoke(accountId,
                () -> adminCommandService.deleteAccount(accountId)
        ).onItem().transformToUni(uni -> uni)
         .onItem().transform(success -> toReply(success ? CommandResult.ok() : CommandResult.fail("INTERNAL_ERROR", "Failed to delete account")));
    }

    @Override
    public Uni<CommonReply> deposit(DepositRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid account ID")));
        }

        long amountMicroUnits = request.getAmountMicroUnits();
        if (amountMicroUnits <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_AMOUNT", "Invalid amount")));
        }

        BigDecimal amount = BigDecimal.valueOf(amountMicroUnits).divide(BigDecimal.valueOf(1_000_000));
        DepositCommand cmd = new DepositCommand(accountId, amount, request.getSource());
        return invoker.invoke(accountId, () -> balanceCommandService.deposit(cmd))
                .onItem().transform(this::toReply);
    }

    @Override
    public Uni<CommonReply> withdraw(WithdrawRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_REQUEST", "Invalid account ID")));
        }

        long amountMicroUnits = request.getAmountMicroUnits();
        if (amountMicroUnits <= 0) {
            return Uni.createFrom().item(toReply(CommandResult.fail("INVALID_AMOUNT", "Invalid amount")));
        }

        BigDecimal amount = BigDecimal.valueOf(amountMicroUnits).divide(BigDecimal.valueOf(1_000_000));
        WithdrawCommand cmd = new WithdrawCommand(accountId, amount, request.getDestination());
        return invoker.invoke(accountId, () -> balanceCommandService.withdraw(cmd))
                .onItem().transform(this::toReply);
    }

    private CommonReply toReply(CommandResult result) {
        return CommonReply.newBuilder().setCode(result.toGrpcCode()).build();
    }
}
