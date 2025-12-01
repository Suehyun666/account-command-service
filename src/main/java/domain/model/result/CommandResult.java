package domain.model.result;

import com.hts.generated.grpc.AccoutResult;

public interface CommandResult {
    boolean success();
    String errorCode();
    String errorMessage();

    default boolean isSuccess() {
        return success();
    }

    default boolean isFailed() {
        return !success();
    }

    default AccoutResult toGrpcCode() {
        if (success()) {
            return AccoutResult.SUCCESS;
        }

        return switch (errorCode()) {
            case "INSUFFICIENT_FUNDS" -> AccoutResult.INSUFFICIENT_FUNDS;
            case "INSUFFICIENT_POSITION" -> AccoutResult.INSUFFICIENT_POSITION;
            case "ACCOUNT_NOT_FOUND" -> AccoutResult.ACCOUNT_NOT_FOUND;
            case "POSITION_NOT_FOUND" -> AccoutResult.POSITION_NOT_FOUND;
            case "INVALID_AMOUNT" -> AccoutResult.INVALID_AMOUNT;
            case "INVALID_REQUEST" -> AccoutResult.INVALID_REQUEST;
            case "DUPLICATE", "ALREADY_PROCESSED" -> AccoutResult.DUPLICATE_REQUEST;
            default -> AccoutResult.INTERNAL_ERROR;
        };
    }

    static CommandResult ok() {
        return new SimpleCommandResult(true, "OK", null);
    }

    static CommandResult duplicate() {
        return new SimpleCommandResult(true, "DUPLICATE", "Already processed");
    }

    static CommandResult fail(String code, String msg) {
        return new SimpleCommandResult(false, code, msg);
    }

    static CommandResult insufficientFunds() {
        return new SimpleCommandResult(false, "INSUFFICIENT_FUNDS", "Not enough balance");
    }

    static CommandResult insufficientPosition() {
        return new SimpleCommandResult(false, "INSUFFICIENT_POSITION", "Not enough position");
    }

    static CommandResult accountNotFound() {
        return new SimpleCommandResult(false, "ACCOUNT_NOT_FOUND", "Account does not exist");
    }

    static CommandResult positionNotFound() {
        return new SimpleCommandResult(false, "POSITION_NOT_FOUND", "Position does not exist");
    }

    static CommandResult alreadyProcessed() {
        return new SimpleCommandResult(true, "ALREADY_PROCESSED", "Event already processed");
    }

    record SimpleCommandResult(
            boolean success,
            String errorCode,
            String errorMessage
    ) implements CommandResult {}
}
