
package com.vikas.stockguard.messaging.publisher;

import com.vikas.stockguard.entity.OutboxEvent;
import com.vikas.stockguard.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {

    private static final Logger log =
            LoggerFactory.getLogger(OutboxPublisher.class);

    private static final String PENDING = "PENDING";
    private static final String PUBLISHED = "PUBLISHED";
    private static final String TOPIC = "inventory.reserved";

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    public OutboxPublisher(
            OutboxEventRepository outboxRepository,
            KafkaTemplate<String, String> kafkaTemplate
    ) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
    }

    @Scheduled(fixedDelay = 5000)
    public void publishPendingEvents() {

        List<OutboxEvent> events =
                outboxRepository
                        .findTop50ByStatusOrderByCreatedAtAsc(PENDING);

        for (OutboxEvent event : events) {
            publishEvent(event);
        }
    }

    private void publishEvent(OutboxEvent event) {

        try {
            kafkaTemplate.send(
                    TOPIC,
                    event.getAggregateId(),
                    event.getPayload()
            ).get(10, TimeUnit.SECONDS);

            markAsPublished(event);

            log.info(
                    "Outbox event published successfully. Event ID: {}",
                    event.getId()
            );

        } catch (Exception exception) {

            markAsFailed(event, exception);

            log.error(
                    "Failed to publish outbox event: {}",
                    event.getId(),
                    exception
            );
        }
    }

    @Transactional
    public void markAsPublished(OutboxEvent event) {

        event.setStatus(PUBLISHED);
        event.setPublishedAt(Instant.now());
        event.setLastError(null);

        outboxRepository.save(event);
    }

    @Transactional
    public void markAsFailed(
            OutboxEvent event,
            Exception exception
    ) {

        event.setAttempts(event.getAttempts() + 1);
        event.setLastError(exception.getMessage());

        outboxRepository.save(event);
    }
}
