package infrastructure.event;

import com.hts.generated.events.auth.AccountCreatedEvent;
import com.hts.generated.events.auth.AccountDeletedEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

@ApplicationScoped
public class KafkaEventProducer {

    private static final Logger LOG = Logger.getLogger(KafkaEventProducer.class);

    @Inject
    @Channel("account-created-events")
    Emitter<byte[]> accountCreatedEmitter;

    @Inject
    @Channel("account-deleted-events")
    Emitter<byte[]> accountDeletedEmitter;

    public void publishAccountCreated(long accountId, String password, String status) {
        try {
            AccountCreatedEvent event = AccountCreatedEvent.newBuilder()
                    .setAccountId(accountId)
                    .setPassword(password)
                    .setStatus(status)
                    .setTimestamp(System.currentTimeMillis())
                    .build();

            byte[] payload = event.toByteArray();
            accountCreatedEmitter.send(Message.of(payload));
            LOG.infof("Published AccountCreatedEvent: accountId=%d", accountId);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish AccountCreatedEvent: accountId=%d", accountId);
        }
    }

    public void publishAccountDeleted(long accountId) {
        try {
            AccountDeletedEvent event = AccountDeletedEvent.newBuilder()
                    .setAccountId(accountId)
                    .setTimestamp(System.currentTimeMillis())
                    .build();

            byte[] payload = event.toByteArray();
            accountDeletedEmitter.send(Message.of(payload));
            LOG.infof("Published AccountDeletedEvent: accountId=%d", accountId);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to publish AccountDeletedEvent: accountId=%d", accountId);
        }
    }
}
