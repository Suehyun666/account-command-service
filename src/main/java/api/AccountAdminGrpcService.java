package api;

import com.hts.generated.grpc.*;
import domain.model.ServiceResult;
import domain.service.AccountCommandService;
import infrastructure.shard.AccountShardInvoker;
import io.quarkus.grpc.GrpcService;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;

@GrpcService
public class AccountAdminGrpcService implements AccountAdminService {

    @Inject AccountCommandService accountCommandService;
    @Inject AccountShardInvoker invoker;

    @Override
    public Uni<CommonReply> createAccount(CreateAccountRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.INVALID_REQUEST)));
        }

        String password = request.getPassword();
        if (password == null || password.isBlank()) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.INVALID_REQUEST)));
        }

        return invoker.invoke(accountId,
                () -> accountCommandService.createAccount(accountId, password, "")
        ).onItem().transform(this::toReply);
    }

    @Override
    public Uni<CommonReply> deleteAccount(DeleteAccountRequest request) {
        long accountId = request.getAccountId();

        if (accountId <= 0) {
            return Uni.createFrom().item(toReply(ServiceResult.of(AccoutResult.INVALID_REQUEST)));
        }

        return invoker.invoke(accountId,
                () -> accountCommandService.deleteAccount(accountId)
        ).onItem().transform(this::toReply);
    }

    private CommonReply toReply(ServiceResult result) {
        return CommonReply.newBuilder()
                .setCode(result.code())
                .build();
    }
}
