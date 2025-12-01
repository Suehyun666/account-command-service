package domain.model.command;

import java.math.BigDecimal;

public record ApplyFillCommand(
        long accountId,
        BigDecimal fillAmount,
        String requestId,
        String orderId,
        String symbol,
        long fillQuantity,
        boolean isBuy
) {}
