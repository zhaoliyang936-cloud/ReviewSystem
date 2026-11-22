-- 1. 辅导/讲解反馈
CREATE TABLE rs_ai_feedback (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES rs_user(id),
    job_id BIGINT NOT NULL REFERENCES rs_ai_job(id),
    category VARCHAR(24) NOT NULL CHECK (category IN ('HELPFUL','UNCLEAR','SUSPECTED_ERROR')),
    comment VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','REVIEWED','CLOSED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (job_id, user_id)
);
CREATE INDEX ix_rs_ai_feedback_job ON rs_ai_feedback(job_id);

-- 2. 题目人工复核案件（3 名学生标记疑似有误后自动建立）
CREATE TABLE rs_question_review_case (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    question_id BIGINT NOT NULL REFERENCES rs_question(id),
    trigger_feedback_count INT NOT NULL CHECK (trigger_feedback_count >= 3),
    reason VARCHAR(200) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','RESOLVED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMPTZ,
    resolved_by BIGINT REFERENCES rs_user(id),
    UNIQUE (question_id)
);

-- 3. 变式错题回链
ALTER TABLE rs_wrong_question
  ADD COLUMN origin_wrong_question_id BIGINT REFERENCES rs_wrong_question(id);
CREATE INDEX ix_rs_wrong_question_origin ON rs_wrong_question(origin_wrong_question_id)
  WHERE origin_wrong_question_id IS NOT NULL;

-- 4. 变式练习标识（约束名已实查为系统默认名 rs_practice_attempt_type_check）
ALTER TABLE rs_practice
  DROP CONSTRAINT rs_practice_attempt_type_check,
  ADD CONSTRAINT rs_practice_attempt_type_check
      CHECK (attempt_type IN ('ORIGINAL_REDO','ORDINARY','AI_VARIANT')),
  ADD COLUMN variant_wrong_question_id BIGINT REFERENCES rs_wrong_question(id);
