package dev.traction.core.topology;

import java.util.HashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/**
 * Declares the per-airport queue set. Declaration is idempotent. Dead-lettering uses the default
 * exchange with routing key = target queue, so no exchanges or bindings are needed.
 *
 * <pre>
 *   runway  (quorum)  --DLX--> parked   (delivery-limit 20: crash loops and broker rejects park)
 *   holding (quorum)  --DLX--> runway   (per-message expiration set at publish time)
 *   parked  (quorum)  no DLX            (replayed or discarded by an operator; delivery limit unlimited)
 * </pre>
 */
@Slf4j
public class Topology {

  private final RabbitAdmin rabbitAdmin;

  public Topology(RabbitAdmin rabbitAdmin) {
    this.rabbitAdmin = rabbitAdmin;
  }

  public QueueNames declare(String airportId) {
    QueueNames names = QueueNames.of(airportId);

    // x-delivery-limit is a declare-time argument and is immutable: an already-declared queue
    // keeps its old value until it is deleted and re-declared.
    //
    // Runway: deliberate deferrals republish a fresh message, so the delivery count only moves on
    // genuine redeliveries (crashes, shutdowns, failed publishes). The limit is strictly-greater:
    // 20 = 21 attempts, then the broker dead-letters to parked. Pinning it gives identical
    // behavior on brokers whose default is unlimited (3.x) and 20 (4.x).
    Map<String, Object> runwayArgs = new HashMap<>();
    runwayArgs.put("x-queue-type", "quorum");
    runwayArgs.put("x-dead-letter-exchange", "");
    runwayArgs.put("x-dead-letter-routing-key", names.parked());
    runwayArgs.put("x-delivery-limit", 20);

    // Holding: no queue-level TTL. The hold backoff is a per-message expiration set at publish
    // time, so one queue serves every backoff round.
    Map<String, Object> holdingArgs = new HashMap<>();
    holdingArgs.put("x-queue-type", "quorum");
    holdingArgs.put("x-dead-letter-exchange", "");
    holdingArgs.put("x-dead-letter-routing-key", names.runway());
    holdingArgs.put("x-delivery-limit", -1);

    // Parked has no DLX, so a broker-default delivery limit of 20 (4.x) would silently discard
    // the flight on the 21st requeue. -1 makes "no limit" true instead of assumed.
    Map<String, Object> parkedArgs = new HashMap<>();
    parkedArgs.put("x-queue-type", "quorum");
    parkedArgs.put("x-delivery-limit", -1);

    // Order is load-bearing: the DLX target (parked) is declared before the queue that
    // dead-letters to it (runway), so the target exists when runway references it.
    rabbitAdmin.declareQueue(new Queue(names.parked(), true, false, false, parkedArgs));
    rabbitAdmin.declareQueue(new Queue(names.holding(), true, false, false, holdingArgs));
    rabbitAdmin.declareQueue(new Queue(names.runway(), true, false, false, runwayArgs));
    log.info("topology declared for airport {}", airportId);
    return names;
  }

  /**
   * Declares the tower queue and its dead-letter queue. Called by every process that publishes to
   * or consumes from the tower queue, so the arguments live in exactly one place (a mismatch is a
   * broker PRECONDITION_FAILED). A report, announcement or charter event that keeps failing is
   * parked on the dead-letter queue, never silently dropped.
   */
  public void declareTower() {
    Map<String, Object> dlqArgs = new HashMap<>();
    dlqArgs.put("x-queue-type", "quorum");
    dlqArgs.put("x-delivery-limit", -1);

    Map<String, Object> towerArgs = new HashMap<>();
    towerArgs.put("x-queue-type", "quorum");
    towerArgs.put("x-delivery-limit", 20);
    towerArgs.put("x-dead-letter-exchange", "");
    towerArgs.put("x-dead-letter-routing-key", QueueNames.deadLetter());

    rabbitAdmin.declareQueue(new Queue(QueueNames.deadLetter(), true, false, false, dlqArgs));
    rabbitAdmin.declareQueue(new Queue(QueueNames.tower(), true, false, false, towerArgs));
  }
}
