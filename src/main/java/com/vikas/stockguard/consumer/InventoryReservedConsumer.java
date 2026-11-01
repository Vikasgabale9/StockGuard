package com.vikas.stockguard.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vikas.stockguard.messaging.event.InventoryReservedEvent;
import com.vikas.stockguard.service.InventoryEventProcessingService;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class InventoryReservedConsumer {

    private final ObjectMapper objectMapper;
    private final InventoryEventProcessingService processingService;

    public InventoryReservedConsumer(
            ObjectMapper objectMapper,
            InventoryEventProcessingService processingService) {
        this.objectMapper = objectMapper;
        this.processingService = processingService;
    }

    @KafkaListener(
            topics = "inventory.reserved",
            groupId = "stockguard-consumer-group"
    )
    public void consume(String message)
            throws JsonProcessingException {

        InventoryReservedEvent event = objectMapper.readValue(
                message,
                InventoryReservedEvent.class
        );

        processingService.process(event);
    }
}