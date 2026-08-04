ALTER TABLE tracked_section
    ADD COLUMN created_at TIMESTAMP;

-- Existing rows predate this column; treat them as just-created so the
-- unconfirmed-watch reaper doesn't delete them on its first run.
UPDATE tracked_section SET created_at = CURRENT_TIMESTAMP WHERE created_at IS NULL;

CREATE INDEX idx_tracked_section_unconfirmed ON tracked_section (confirmed, created_at);
