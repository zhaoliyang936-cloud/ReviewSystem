CREATE TABLE rs_learning_activity (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES rs_user(id),
    subject_id BIGINT NOT NULL DEFAULT 1 REFERENCES rs_subject(id),
    kind VARCHAR(16) NOT NULL CHECK (kind IN ('PRACTICE', 'TASK')),
    status VARCHAR(24) NOT NULL CHECK (status IN ('PREPARING', 'READY', 'IN_PROGRESS', 'GRADING', 'COMPLETED', 'CANCELLED', 'PREPARATION_FAILED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_rs_activity_active
ON rs_learning_activity(user_id, subject_id)
WHERE status IN ('PREPARING', 'READY', 'IN_PROGRESS', 'GRADING');

CREATE TABLE rs_practice (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    activity_id BIGINT NOT NULL UNIQUE REFERENCES rs_learning_activity(id),
    user_id BIGINT NOT NULL REFERENCES rs_user(id),
    subject_id BIGINT NOT NULL DEFAULT 1 REFERENCES rs_subject(id),
    question_count INTEGER NOT NULL CHECK (question_count BETWEEN 1 AND 20),
    mix_ratio_json JSONB NOT NULL,
    request_key VARCHAR(120) NOT NULL,
    submit_key VARCHAR(120),
    submit_hash CHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    submitted_at TIMESTAMPTZ,
    UNIQUE (user_id, request_key)
);

CREATE TABLE rs_practice_item (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    practice_id BIGINT NOT NULL REFERENCES rs_practice(id),
    question_id BIGINT NOT NULL REFERENCES rs_question(id),
    question_version_id BIGINT NOT NULL REFERENCES rs_question_version(id),
    knowledge_id BIGINT NOT NULL REFERENCES rs_knowledge_point(id),
    question_type VARCHAR(20) NOT NULL CHECK (question_type IN ('CHOICE', 'FILL', 'SHORT_ANSWER', 'ALGORITHM')),
    max_score NUMERIC(8,2) NOT NULL CHECK (max_score > 0),
    position INTEGER NOT NULL CHECK (position >= 0),
    draft_json JSONB,
    draft_version INTEGER NOT NULL DEFAULT 0 CHECK (draft_version >= 0),
    final_answer_json JSONB,
    result_json JSONB,
    grading_status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED' CHECK (grading_status IN ('NOT_STARTED', 'AUTO_GRADED', 'AI_REVIEW_PENDING')),
    UNIQUE (practice_id, position)
);

CREATE TABLE rs_answer_asset (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES rs_user(id),
    practice_item_id BIGINT NOT NULL REFERENCES rs_practice_item(id),
    position INTEGER NOT NULL CHECK (position BETWEEN 0 AND 2),
    storage_key VARCHAR(100) NOT NULL UNIQUE,
    mime VARCHAR(20) NOT NULL CHECK (mime IN ('image/png', 'image/jpeg')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    sha256 CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL DEFAULT 'UPLOADED' CHECK (state IN ('UPLOADED', 'SUBMITTED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (practice_item_id, position)
);

CREATE INDEX rs_activity_user_history_idx ON rs_learning_activity(user_id, updated_at DESC);
CREATE INDEX rs_practice_user_submitted_idx ON rs_practice(user_id, submitted_at DESC);
CREATE INDEX rs_practice_item_question_idx ON rs_practice_item(question_id, question_version_id);
CREATE INDEX rs_answer_asset_item_idx ON rs_answer_asset(practice_item_id);
