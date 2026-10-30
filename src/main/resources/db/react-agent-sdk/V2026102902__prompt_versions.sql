CREATE TABLE IF NOT EXISTS prompt_versions (
                                               id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
                                               path TEXT NOT NULL,
                                               commit_id TEXT NOT NULL,
                                               is_active BOOLEAN NOT NULL DEFAULT TRUE,
                                               created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS prompt_versions_active_path
    ON prompt_versions (path) WHERE is_active = TRUE;