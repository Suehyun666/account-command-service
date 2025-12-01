package domain.service;

import com.hts.generated.events.order.OrderFillEvent;
import com.hts.generated.grpc.Side;
import domain.model.command.ApplyFillCommand;
import domain.model.result.CommandResult;
import infrastructure.repository.FillWriteRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.math.BigDecimal;

@ApplicationScoped
public class FillCommandService {

    private static final Logger LOG = Logger.getLogger(FillCommandService.class);

    @Inject FillWriteRepository fillRepo;

    public void processOrderFillEvent(OrderFillEvent event) {
        LOG.infof("Processing OrderFillEvent: eventId=%s, accountId=%d, side=%s",
                event.getEventId(), event.getAccountId(), event.getSide());

        BigDecimal totalFillAmount = BigDecimal.ZERO;
        long totalFillQty = 0;

        for (var fill : event.getFillsList()) {
            BigDecimal price = BigDecimal.valueOf(fill.getPriceMicroUnits()).divide(BigDecimal.valueOf(1_000_000));
            long qty = fill.getQuantity();
            totalFillAmount = totalFillAmount.add(price.multiply(BigDecimal.valueOf(qty)));
            totalFillQty += qty;
        }

        ApplyFillCommand cmd = new ApplyFillCommand(
                event.getAccountId(),
                totalFillAmount,
                event.getEventId(),
                event.getClientOrderId(),
                "SEC" + event.getSecurityId(),
                totalFillQty,
                event.getSide() == Side.BUY
        );

        CommandResult result = fillRepo.applyFill(cmd);

        if (!result.success()) {
            LOG.errorf("Failed to apply fill: eventId=%s, error=%s - %s",
                    event.getEventId(), result.errorCode(), result.errorMessage());
            throw new RuntimeException("Fill processing failed: " + result.errorCode());
        }

        LOG.infof("Fill processed successfully: eventId=%s, qty=%d, amount=%s",
                event.getEventId(), totalFillQty, totalFillAmount);
    }
}
