
package com.vikas.stockguard.repository;

import com.vikas.stockguard.entity.DeadLetterEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeadLetterEventRepository
        extends JpaRepository<DeadLetterEvent, Long> {

    @Modifying
    @Query(value = """
            INSERT INTO dead_letter_events (
                event_id,
                original_topic,
                message_key,
                dlt_partition,
                dlt_offset,
                payload,
                exception_class,
                exception_message,
                failure_status,
                failed_at
            )
            VALUES (
                :eventId,
                :originalTopic,
                :messageKey,
                :partition,
                :offset,
                CAST(:payload AS jsonb),
                :exceptionClass,
                :exceptionMessage,
                'PENDING',
                CURRENT_TIMESTAMP
            )
            ON CONFLICT (event_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfNotExists(
            @Param("eventId") java.util.UUID eventId,
            @Param("originalTopic") String originalTopic,
            @Param("messageKey") String messageKey,
            @Param("partition") int partition,
            @Param("offset") long offset,
            @Param("payload") String payload,
            @Param("exceptionClass") String exceptionClass,
            @Param("exceptionMessage") String exceptionMessage
    );
}
