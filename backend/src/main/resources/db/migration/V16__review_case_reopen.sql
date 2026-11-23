-- 同一题允许保留「最多 1 条 OPEN + 多条已结案(RESOLVED)」历史案件，保证已结案题目在新增举报后可重新开案且历史案件可追溯
ALTER TABLE rs_question_review_case
    DROP CONSTRAINT rs_question_review_case_question_id_key;

CREATE UNIQUE INDEX uq_rs_question_review_case_open
    ON rs_question_review_case(question_id) WHERE status = 'OPEN';
