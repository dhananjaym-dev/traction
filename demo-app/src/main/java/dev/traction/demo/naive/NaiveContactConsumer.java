package dev.traction.demo.naive;

import dev.traction.demo.governed.AcmeClient;
import dev.traction.demo.governed.AcmeClient.Contact;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The typical hand-rolled consumer: the baseline Traction is contrasted against. Demo material,
 * not a strawman. It does what most teams do after their first incident:
 *
 * <ul>
 *   <li>a retry limit: any exception requeues the message, and the broker's delivery limit
 *       (3, see {@link NaiveTopology}) stops it going around forever;
 *   <li>a DLQ: past the limit the broker dead-letters the message instead of losing it;
 *   <li>an ERROR log, with the stack trace, so someone can see it happening.
 * </ul>
 *
 * Why it still fails, at the shared-fate level: the Acme token is one credential for every
 * message, but each message finds that out alone. With the token expired, all N messages
 * call Acme, fail, requeue, call again, fail, and so on until each reaches the delivery limit:
 * about 4 calls per message, and an ERROR with a stack trace for every one. Nothing notices
 * that the failures share a cause, so nothing stops the line; the vendor is hammered with
 * doomed calls and the logs fill with noise. Recovery is manual: fix the token, then shovel
 * the DLQ back into the work queue by hand and hope nothing was in there for another reason.
 */
@Component
public class NaiveContactConsumer {

    private static final Logger log = LoggerFactory.getLogger(NaiveContactConsumer.class);

    private final AcmeClient acme;

    public NaiveContactConsumer(AcmeClient acme) {
        this.acme = acme;
    }

    @RabbitListener(queues = NaiveTopology.QUEUE, containerFactory = NaiveTopology.LISTENER_FACTORY)
    public void onContact(Contact contact) {
        try {
            acme.upsert(AcmeClient.CONTACTS, contact);
        } catch (RuntimeException e) {
            // The one log line scenario 1 counts: "naive.contact-sync FAILED id=<id> error=<exception>".
            log.error("naive.contact-sync FAILED id={} error={}", contact.id(), e.toString(), e);
            throw e; // rethrow: the container nacks with requeue; the broker counts deliveries
        }
    }
}
