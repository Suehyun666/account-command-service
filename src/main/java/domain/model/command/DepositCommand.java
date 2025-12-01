package domain.model.command;

import java.math.BigDecimal;

public record DepositCommand(
        long accountId,
        BigDecimal amount,
        String source
) {}
