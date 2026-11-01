
package com.vikas.stockguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vikas.stockguard.repository.DeadLetterEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class DeadLetterEventService {

    private final DeadLetterEventRepository repository;
    private final ObjectMapper objectMapper;

    public DeadLetterEventService(
            DeadLetterEventRepository repository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void save(
            String originalTopic,
            String messageKey,
            int partition,
            long offset,
            String payload,
            String exceptionClass,
            String exceptionMessage) {

        try {
            JsonNode jsonPayload = objectMapper.readTree(payload);

            UUID eventId = UUID.fromString(
                    jsonPayload.path("eventId").asText()
            );

            int inserted = repository.insertIfNotExists(
                    eventId,
                    originalTopic,
                    messageKey,
                    partition,
                    offset,
                    payload,
                    exceptionClass,
                    exceptionMessage
            );

            if (inserted == 0) {
                System.out.println(
                        "Duplicate DLT event ignored: " + eventId
                );
                return;
            }

            System.out.println(
                    "DLT event persisted successfully: " + eventId
            );

        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Failed to persist dead-letter event",
                    exception
            );
        }
    }
}