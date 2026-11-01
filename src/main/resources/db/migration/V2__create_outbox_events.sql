CREATE TABLE outbox_events (
                               id UUID PRIMARY KEY,
                               event_type VARCHAR(100) NOT NULL,
                               aggregate_type VARCHAR(100) NOT NULL,
                               aggregate_id VARCHAR(100) NOT NULL,
                               payload TEXT NOT NULL,
                               status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
                               attempts INTEGER NOT NULL DEFAULT 0,
                               last_error TEXT,
                               created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
                               published_at TIMESTAMP WITH TIME ZONE,

                               CONSTRAINT chk_outbox_status
                                   CHECK (status IN ('PENDING', 'PUBLISHED'))
);

CREATE INDEX idx_outbox_pending
    ON outbox_events (created_at)
    WHERE status = 'PENDING';