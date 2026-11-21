-- M10 主观题评分需要区分「AI 已受理待评」与「AI 超时/失败」两种状态。
-- 技术设计第 191 行要求失败时把题项置为 AI_REVIEW_FAILED，允许手动重试。
-- 这里放开两张题项表 grading_status 的取值域，不改动已有数据。

DO $$
DECLARE target record;
BEGIN
    FOR target IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'rs_practice_item'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%grading_status%'
    LOOP
        EXECUTE format('ALTER TABLE rs_practice_item DROP CONSTRAINT %I', target.conname);
    END LOOP;

    FOR target IN
        SELECT conname FROM pg_constraint
        WHERE conrelid = 'rs_task_item'::regclass AND contype = 'c'
          AND pg_get_constraintdef(oid) LIKE '%grading_status%'
    LOOP
        EXECUTE format('ALTER TABLE rs_task_item DROP CONSTRAINT %I', target.conname);
    END LOOP;
END $$;

ALTER TABLE rs_practice_item ADD CONSTRAINT ck_rs_practice_item_grading_status
    CHECK (grading_status IN ('NOT_STARTED', 'AUTO_GRADED', 'AI_REVIEW_PENDING', 'AI_REVIEW_FAILED'));

ALTER TABLE rs_task_item ADD CONSTRAINT ck_rs_task_item_grading_status
    CHECK (grading_status IN ('NOT_STARTED', 'AUTO_GRADED', 'AI_REVIEW_PENDING', 'AI_REVIEW_FAILED'));
