package dev.traction.core.charter;

import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.topology.QueueNames;

/**
 * Publishes charter events to the tower queue, waiting for the broker's confirm. A failed publish
 * propagates to the caller.
 */
public class CharterEventPublisher {

  private final ConfirmedPublisher confirmedPublisher;

  public CharterEventPublisher(ConfirmedPublisher confirmedPublisher) {
    this.confirmedPublisher = confirmedPublisher;
  }

  public void publish(CharterEventMessage event) {
    confirmedPublisher.publish(QueueNames.tower(), event, null);
  }
}
