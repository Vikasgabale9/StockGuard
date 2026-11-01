
package com.vikas.stockguard.service;

import com.vikas.stockguard.messaging.event.InventoryReservedEvent;
import com.vikas.stockguard.repository.ProcessedEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InventoryEventProcessingService {

    private final ProcessedEventRepository processedEventRepository;

    public InventoryEventProcessingService(
            ProcessedEventRepository processedEventRepository) {
        this.processedEventRepository = processedEventRepository;
    }

    @Transactional
    public void process(InventoryReservedEvent event) {

        // Atomically register the event to prevent duplicate processing
        int inserted = processedEventRepository.insertIfNotProcessed(
                event.eventId(),
                event.eventType()
        );

        if (inserted == 0) {
            System.out.println(
                    "Duplicate event ignored: " + event.eventId()
            );
            return;
        }

        // Event processing logic goes here.
        System.out.println("Inventory reservation event processed:");
        System.out.println("Event ID: " + event.eventId());
        System.out.println("Reservation ID: " + event.reservationId());
        System.out.println("SKU: " + event.sku());
        System.out.println("Quantity: " + event.quantity());
    }
}
