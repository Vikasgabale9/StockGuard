package com.vikas.stockguard.messaging.event;

import java.time.Instant;
import java.util.UUID;

public record InventoryReservedEvent(
        UUID eventId,
        Long reservationId,
        String sku,
        int quantity,
        String eventType,
        Instant occurredAt
) {
}