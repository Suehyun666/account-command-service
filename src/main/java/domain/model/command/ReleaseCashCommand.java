package domain.model.command;

public record ReleaseCashCommand(
        long accountId,
        String requestId
) {}
