CREATE TABLE rs_review_task (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 activity_id BIGINT NOT NULL UNIQUE REFERENCES rs_learning_activity(id),
 user_id BIGINT NOT NULL REFERENCES rs_user(id),
 subject_id BIGINT NOT NULL DEFAULT 1 REFERENCES rs_subject(id),
 status VARCHAR(24) NOT NULL DEFAULT 'READY',
 question_count INTEGER NOT NULL CHECK (question_count BETWEEN 1 AND 20),
 mix_ratio_json JSONB NOT NULL,
 cutoff_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 rule_version VARCHAR(32) NOT NULL,
 source_revision BIGINT NOT NULL DEFAULT 0,
 request_key VARCHAR(120) NOT NULL,
 request_hash CHAR(64) NOT NULL,
 result_json JSONB,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 submitted_at TIMESTAMPTZ,
 submit_key VARCHAR(120),
 submit_hash CHAR(64),
 UNIQUE(user_id,request_key)
);
CREATE TABLE rs_task_item (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 task_id BIGINT NOT NULL REFERENCES rs_review_task(id),
 question_id BIGINT NOT NULL REFERENCES rs_question(id),
 question_version_id BIGINT NOT NULL REFERENCES rs_question_version(id),
 knowledge_id BIGINT NOT NULL REFERENCES rs_knowledge_point(id),
 question_type VARCHAR(20) NOT NULL CHECK (question_type IN ('CHOICE','FILL','SHORT_ANSWER','ALGORITHM')),
 max_score NUMERIC(8,2) NOT NULL CHECK (max_score > 0),
 position INTEGER NOT NULL CHECK (position >= 0),
 draft_json JSONB,
 draft_version INTEGER NOT NULL DEFAULT 0 CHECK (draft_version >= 0),
 final_answer_json JSONB,
 result_json JSONB,
 reason_json JSONB NOT NULL,
 grading_status VARCHAR(24) NOT NULL DEFAULT 'NOT_STARTED' CHECK (grading_status IN ('NOT_STARTED','AUTO_GRADED','AI_REVIEW_PENDING')),
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(task_id,position), UNIQUE(task_id,question_id)
);
CREATE INDEX rs_task_item_task_idx ON rs_task_item(task_id);
CREATE INDEX rs_review_task_user_created_idx ON rs_review_task(user_id,created_at DESC);
