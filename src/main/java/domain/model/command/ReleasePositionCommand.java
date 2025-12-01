package domain.model.command;

public record ReleasePositionCommand(
        long accountId,
        String requestId
) {}
