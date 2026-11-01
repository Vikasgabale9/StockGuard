
package com.vikas.stockguard.consumer;

import com.vikas.stockguard.service.DeadLetterEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class InventoryReservedDltConsumer {

    private final DeadLetterEventService deadLetterEventService;

    public InventoryReservedDltConsumer(
            DeadLetterEventService deadLetterEventService) {
        this.deadLetterEventService = deadLetterEventService;
    }

    @KafkaListener(
            topics = "inventory.reserved-dlt",
            groupId = "stockguard-dlt-consumer-group",
            containerFactory = "dltKafkaListenerContainerFactory"
    )
    public void consume(ConsumerRecord<String, String> record) {

        String originalTopic = getHeader(
                record, "kafka_dlt-original-topic"
        );

        if (originalTopic == null || originalTopic.isBlank()) {
            originalTopic = "UNKNOWN";

            System.out.println(
                    "Warning: DLT message has no original-topic header. " +
                            "Using UNKNOWN. Offset: " + record.offset()
            );
        }

        String exceptionClass = getHeader(
                record, "kafka_dlt-exception-fqcn"
        );

        String exceptionMessage = getHeader(
                record, "kafka_dlt-exception-message"
        );

        deadLetterEventService.save(
                originalTopic,
                record.key(),
                record.partition(),
                record.offset(),
                record.value(),
                exceptionClass,
                exceptionMessage
        );
    }

    private String getHeader(
            ConsumerRecord<String, String> record,
            String headerName) {

        Header header = record.headers().lastHeader(headerName);

        if (header == null) {
            return null;
        }

        return new String(
                header.value(),
                StandardCharsets.UTF_8
        );
    }
}
