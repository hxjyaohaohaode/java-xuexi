package io.typeforge.operations.events;

import com.fasterxml.jackson.databind.JsonNode;

/** Implementations run inside the same transaction as their unique receipt. No network side effects. */
public interface DomainEventConsumer {
    String name();
    boolean accepts(String eventType);
    void accept(JsonNode envelope);
}
