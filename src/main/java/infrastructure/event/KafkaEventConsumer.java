package infrastructure.event;

import domain.service.FillCommandService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.hts.generated.events.order.OrderFillEvent;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;
import java.util.concurrent.CompletionStage;

@ApplicationScoped
public class KafkaEventConsumer {

    private static final Logger LOG = Logger.getLogger(KafkaEventConsumer.class);

    @Inject FillCommandService commandService;

    @Incoming("order-filled-events")
    public CompletionStage<Void> onOrderFilled(Message<byte[]> message) {
        try {
            OrderFillEvent event = OrderFillEvent.parseFrom(message.getPayload());

            LOG.infof("Received OrderFillEvent: eventId=%s, accountId=%d",
                    event.getEventId(), event.getAccountId());

            commandService.processOrderFillEvent(event);

            return message.ack();
        } catch (Exception e) {
            LOG.errorf(e, "Failed to process OrderFillEvent");
            return message.nack(e);
        }
    }
}
