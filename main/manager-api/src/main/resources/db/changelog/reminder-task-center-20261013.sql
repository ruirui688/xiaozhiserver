-- Existing reminder IDs and source request fingerprints remain stable. Do not mix old/new scheduler versions.
ALTER TABLE ai_device_reminder
    ADD COLUMN recurrence_json VARCHAR(1024) NULL COMMENT 'Restricted calendar rule; null means ONCE',
    ADD COLUMN schedule_anchor_at DATETIME(3) NULL COMMENT 'Immutable local recurrence anchor until edited',
    ADD COLUMN schedule_version INT NOT NULL DEFAULT 1 COMMENT 'Incremented on edits, independent from cursor advancement';
UPDATE ai_device_reminder SET schedule_anchor_at = remind_at;

CREATE TABLE ai_reminder_execution (
    reminder_id VARCHAR(32) NOT NULL,
    schedule_version INT NOT NULL DEFAULT 1,
    scheduled_at DATETIME(3) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    last_error_code VARCHAR(64) NULL,
    finished_at DATETIME(3) NULL,
    PRIMARY KEY (reminder_id, scheduled_at, schedule_version),
    KEY idx_reminder_execution_retention (finished_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Bounded execution history, no reminder content';

CREATE TABLE ai_reminder_dispatch_gate (
    id INT NOT NULL PRIMARY KEY,
    next_scan_at DATETIME(3) NOT NULL,
    window_second BIGINT NOT NULL DEFAULT 0,
    permits INT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Global scan and dispatch rate budgets; separate rows avoid lock coupling';
INSERT INTO ai_reminder_dispatch_gate (id, next_scan_at) VALUES
    (1, '2000-01-01'), (2, '2000-01-01'), (3, '2000-01-01'), (4, '2000-01-01');
