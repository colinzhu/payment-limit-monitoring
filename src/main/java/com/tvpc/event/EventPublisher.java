package com.tvpc.event;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publisher for settlement events to Vert.x event bus
 */
public class EventPublisher {
    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    public static final String SETTLEMENT_EVENT_ADDRESS = "settlement.events";

    private final Vertx vertx;

    public EventPublisher(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * Publish a single settlement event
     */
    public Future<Void> publish(SettlementEvent event) {
        log.debug("Publishing settlement event: {}", event);

        vertx.eventBus().publish(SETTLEMENT_EVENT_ADDRESS, event);
        return Future.succeededFuture();
    }

    /**
     * Publish multiple settlement events
     */
    public Future<Void> publishMultiple(java.util.List<SettlementEvent> events) {
        if (events.isEmpty()) {
            return Future.succeededFuture();
        }

        log.debug("Publishing {} settlement events", events.size());

        // Publish all events
        for (SettlementEvent event : events) {
            vertx.eventBus().publish(SETTLEMENT_EVENT_ADDRESS, event);
        }

        return Future.succeededFuture();
    }

    /**
     * Send event with confirmation (for critical operations)
     */
    public Future<String> sendWithConfirmation(SettlementEvent event) {
        log.debug("Sending settlement event with confirmation: {}", event);

        return vertx.eventBus()
                .request(SETTLEMENT_EVENT_ADDRESS, event)
                .map(message -> (String) message.body());
    }
}
