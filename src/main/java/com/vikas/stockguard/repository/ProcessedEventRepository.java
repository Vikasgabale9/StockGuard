package com.vikas.stockguard.repository;

import com.vikas.stockguard.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface ProcessedEventRepository
        extends JpaRepository<ProcessedEvent, UUID> {

    boolean existsByEventId(UUID eventId);

    @Modifying
    @Query(value = """
        INSERT INTO processed_events
        (event_id, event_type, processed_at)
        VALUES (:eventId, :eventType, CURRENT_TIMESTAMP)
        ON CONFLICT (event_id) DO NOTHING
        """, nativeQuery = true)
    int insertIfNotProcessed(
            @Param("eventId") UUID eventId,
            @Param("eventType") String eventType
    );
}