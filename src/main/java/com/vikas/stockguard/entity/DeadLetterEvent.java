
package com.vikas.stockguard.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(
        name = "dead_letter_events",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uk_dlt_partition_offset",
                        columnNames = {"original_topic", "dlt_partition", "dlt_offset"}
                )
        }
)
public class DeadLetterEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "original_topic", nullable = false)
    private String originalTopic;

    @Column(name = "message_key")
    private String messageKey;

    @Column(name = "dlt_partition", nullable = false)
    private Integer dltPartition;

    @Column(name = "dlt_offset", nullable = false)
    private Long dltOffset;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    private JsonNode payload;

    @Column(name = "exception_class")
    private String exceptionClass;

    @Column(name = "exception_message")
    private String exceptionMessage;

    @Column(name = "failure_status", nullable = false)
    private String failureStatus = "PENDING";

    @Column(name = "failed_at", nullable = false)
    private Instant failedAt = Instant.now();

    public DeadLetterEvent() {
    }

    public Long getId() {
        return id;
    }

    public UUID getEventId() {
        return eventId;
    }

    public void setEventId(UUID eventId) {
        this.eventId = eventId;
    }

    public String getOriginalTopic() {
        return originalTopic;
    }

    public void setOriginalTopic(String originalTopic) {
        this.originalTopic = originalTopic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public void setMessageKey(String messageKey) {
        this.messageKey = messageKey;
    }

    public Integer getDltPartition() {
        return dltPartition;
    }

    public void setDltPartition(Integer dltPartition) {
        this.dltPartition = dltPartition;
    }

    public Long getDltOffset() {
        return dltOffset;
    }

    public void setDltOffset(Long dltOffset) {
        this.dltOffset = dltOffset;
    }

    public JsonNode getPayload() {
        return payload;
    }

    public void setPayload(JsonNode payload) {
        this.payload = payload;
    }

    public String getExceptionClass() {
        return exceptionClass;
    }

    public void setExceptionClass(String exceptionClass) {
        this.exceptionClass = exceptionClass;
    }

    public String getExceptionMessage() {
        return exceptionMessage;
    }

    public void setExceptionMessage(String exceptionMessage) {
        this.exceptionMessage = exceptionMessage;
    }

    public String getFailureStatus() {
        return failureStatus;
    }

    public void setFailureStatus(String failureStatus) {
        this.failureStatus = failureStatus;
    }

    public Instant getFailedAt() {
        return failedAt;
    }

    public void setFailedAt(Instant failedAt) {
        this.failedAt = failedAt;
    }
}
