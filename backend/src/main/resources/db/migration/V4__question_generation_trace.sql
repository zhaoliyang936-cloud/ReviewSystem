ALTER TABLE rs_question
    ADD COLUMN origin_question_version_id BIGINT NULL REFERENCES rs_question_version(id),
    ADD COLUMN gen_job_id BIGINT NULL;

CREATE INDEX rs_question_origin_version_idx ON rs_question(origin_question_version_id);
CREATE INDEX rs_question_gen_job_idx ON rs_question(gen_job_id);
