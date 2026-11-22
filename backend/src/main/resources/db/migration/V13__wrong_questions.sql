CREATE TABLE rs_wrong_question (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES rs_user(id),
    question_id BIGINT NOT NULL REFERENCES rs_question(id),
    question_version_id BIGINT NOT NULL REFERENCES rs_question_version(id),
    knowledge_id BIGINT NOT NULL REFERENCES rs_knowledge_point(id),
    source_type VARCHAR(16) NOT NULL CHECK (source_type IN ('MANUAL','AI_GENERATED')),
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','MASTERED')),
    wrong_count INT NOT NULL DEFAULT 1 CHECK (wrong_count > 0),
    first_wrong_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_wrong_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    latest_attempt_id BIGINT,
    mastered_at TIMESTAMPTZ,
    UNIQUE (user_id, question_id)
);
CREATE INDEX ix_rs_wrong_question_pending ON rs_wrong_question(user_id, status, last_wrong_at DESC);

CREATE TABLE rs_wrong_attempt (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    wrong_question_id BIGINT NOT NULL REFERENCES rs_wrong_question(id),
    practice_id BIGINT REFERENCES rs_practice(id),
    item_id BIGINT REFERENCES rs_practice_item(id),
    task_item_id BIGINT REFERENCES rs_task_item(id),
    attempt_type VARCHAR(16) NOT NULL CHECK (attempt_type IN ('ORIGINAL_REDO','AI_VARIANT','ORDINARY')),
    answer_json JSONB,
    grade_json JSONB,
    passed BOOLEAN,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT ck_rs_wrong_attempt_owner CHECK (
        (practice_id IS NOT NULL AND item_id IS NOT NULL AND task_item_id IS NULL)
        OR (practice_id IS NULL AND item_id IS NULL AND task_item_id IS NOT NULL))
);
CREATE INDEX ix_rs_wrong_attempt_wq ON rs_wrong_attempt(wrong_question_id, created_at DESC);

ALTER TABLE rs_wrong_question
  ADD CONSTRAINT fk_rs_wrong_question_latest_attempt
  FOREIGN KEY (latest_attempt_id) REFERENCES rs_wrong_attempt(id);

ALTER TABLE rs_practice
  ADD COLUMN attempt_type VARCHAR(16) NOT NULL DEFAULT 'ORDINARY'
    CHECK (attempt_type IN ('ORIGINAL_REDO','ORDINARY')),
  ADD COLUMN redo_wrong_question_id BIGINT REFERENCES rs_wrong_question(id);

CREATE TABLE rs_question_feedback (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES rs_user(id),
    wrong_question_id BIGINT NOT NULL REFERENCES rs_wrong_question(id),
    question_id BIGINT NOT NULL REFERENCES rs_question(id),
    category VARCHAR(24) NOT NULL CHECK (category IN ('SUSPECTED_ERROR','CONTENT_PROBLEM','OTHER')),
    comment VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVIEWED','CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (wrong_question_id, user_id)
);
