package domain.model.command;

import java.math.BigDecimal;

public record WithdrawCommand(
        long accountId,
        BigDecimal amount,
        String destination
) {}
