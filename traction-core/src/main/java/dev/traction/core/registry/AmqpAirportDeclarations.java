package dev.traction.core.registry;

import dev.traction.core.publish.ConfirmedPublisher;
import dev.traction.core.topology.QueueNames;

/**
 * Publishes announcements to the tower queue with a broker confirm (fail-fast): a publish or
 * confirm failure propagates and aborts startup. Once the broker acks, the durable tower queue
 * guarantees eventual projection even while the control plane is down.
 */
public class AmqpAirportDeclarations implements AirportDeclarations {

  private final ConfirmedPublisher confirmedPublisher;

  public AmqpAirportDeclarations(ConfirmedPublisher confirmedPublisher) {
    this.confirmedPublisher = confirmedPublisher;
  }

  @Override
  public void announce(AirportAnnouncement announcement) {
    confirmedPublisher.publish(QueueNames.tower(), announcement, null);
  }
}
