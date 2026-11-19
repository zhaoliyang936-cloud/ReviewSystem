CREATE TABLE rs_evidence (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES rs_user(id), source_type VARCHAR(32) NOT NULL,
 submission_id BIGINT NOT NULL, question_id BIGINT NOT NULL REFERENCES rs_question(id),
 question_version_id BIGINT NOT NULL REFERENCES rs_question_version(id),
 knowledge_id BIGINT NOT NULL REFERENCES rs_knowledge_point(id), question_type VARCHAR(20) NOT NULL,
 result_y NUMERIC(5,4), weight NUMERIC(3,2) NOT NULL,
 source_revision INT NOT NULL, revoked BOOLEAN NOT NULL DEFAULT FALSE,
 submitted_at TIMESTAMPTZ NOT NULL, graded_at TIMESTAMPTZ NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(source_type,submission_id,question_id)
);
CREATE INDEX rs_evidence_user_knowledge_time_idx ON rs_evidence(user_id,knowledge_id,submitted_at DESC);
CREATE TABLE rs_mastery_snapshot (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 user_id BIGINT NOT NULL REFERENCES rs_user(id), knowledge_id BIGINT NOT NULL REFERENCES rs_knowledge_point(id),
 n INT NOT NULL, weight_sum NUMERIC(8,3) NOT NULL, weighted_sum NUMERIC(8,3) NOT NULL,
 rule_score NUMERIC(6,3), ai_score NUMERIC(6,3), final_score NUMERIC(6,3),
 clamped BOOLEAN NOT NULL, status VARCHAR(20) NOT NULL, ai_reason_json JSONB NOT NULL,
 ai_job_id BIGINT, evidence_revision BIGINT NOT NULL, rule_version VARCHAR(32) NOT NULL,
 evidence_ids_json JSONB NOT NULL, latest_practice_at TIMESTAMPTZ,
 cutoff_at TIMESTAMPTZ NOT NULL, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX rs_mastery_snapshot_user_knowledge_idx ON rs_mastery_snapshot(user_id,knowledge_id,id DESC);
CREATE FUNCTION rs_reject_mastery_snapshot_update() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'mastery snapshots are immutable'; END $$;
CREATE TRIGGER rs_mastery_snapshot_immutable BEFORE UPDATE ON rs_mastery_snapshot FOR EACH ROW EXECUTE FUNCTION rs_reject_mastery_snapshot_update();
CREATE TABLE rs_mastery_current (
 user_id BIGINT NOT NULL REFERENCES rs_user(id), knowledge_id BIGINT NOT NULL REFERENCES rs_knowledge_point(id),
 snapshot_id BIGINT NOT NULL REFERENCES rs_mastery_snapshot(id), evidence_revision BIGINT NOT NULL,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(user_id,knowledge_id)
);
CREATE TABLE rs_student_course_guard (
 user_id BIGINT NOT NULL REFERENCES rs_user(id), subject_id BIGINT NOT NULL REFERENCES rs_subject(id),
 learning_revision BIGINT NOT NULL DEFAULT 0, tutoring_epoch BIGINT NOT NULL DEFAULT 0,
 PRIMARY KEY(user_id,subject_id)
);
CREATE TABLE rs_change_event (
 id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, event_type VARCHAR(40) NOT NULL,
 source_id BIGINT NOT NULL, source_revision INT NOT NULL, state VARCHAR(16) NOT NULL,
 attempts INT NOT NULL DEFAULT 0, next_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 last_error TEXT, payload_json JSONB, created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(event_type,source_id,source_revision)
);
CREATE INDEX rs_change_event_retry_idx ON rs_change_event(state,next_retry_at);
