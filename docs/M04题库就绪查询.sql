\set ON_ERROR_STOP on
\pset pager off
\pset null '(null)'

-- 不能用 READ ONLY：PostgreSQL 在只读事务里拒绝 CREATE VIEW（含 TEMP VIEW）。
-- 本脚本只创建临时视图并做 SELECT，末尾 ROLLBACK，不写任何业务表。
BEGIN;

CREATE TEMP VIEW m04_question_shape AS
SELECT q.id AS question_id,
       q.question_no,
       q.origin,
       q.status,
       v.id AS question_version_id,
       v.knowledge_point_id,
       v.type,
       v.max_score,
       v.content_json,
       v.answer_json,
       v.rubric_json,
       btrim(CASE WHEN jsonb_typeof(v.content_json -> 'stem') = 'string' THEN v.content_json ->> 'stem' ELSE '' END) AS stem,
       CASE WHEN jsonb_typeof(v.content_json -> 'options') = 'array' THEN v.content_json -> 'options' ELSE '[]'::jsonb END AS options,
       CASE WHEN jsonb_typeof(v.answer_json -> 'blanks') = 'array' THEN v.answer_json -> 'blanks' ELSE '[]'::jsonb END AS blanks,
       CASE WHEN jsonb_typeof(v.rubric_json -> 'items') = 'array' THEN v.rubric_json -> 'items' ELSE '[]'::jsonb END AS rubric_items
FROM rs_question q
JOIN rs_question_version v ON v.id = q.current_version_id
WHERE q.subject_id = 1;

CREATE TEMP VIEW m04_question_audit AS
SELECT s.*,
       COALESCE(
         jsonb_typeof(s.content_json) = 'object'
         AND jsonb_typeof(s.answer_json) = 'object'
         AND s.stem <> ''
         AND length(s.stem) <= 10000
         AND s.max_score > 0
         AND s.max_score <= 1000
         AND (
           CASE s.type
             WHEN 'CHOICE' THEN
               jsonb_typeof(s.content_json -> 'options') = 'array'
               AND jsonb_array_length(s.options) >= 3
               AND NOT EXISTS (
                 SELECT 1 FROM jsonb_array_elements(s.options) option
                 WHERE btrim(CASE WHEN jsonb_typeof(option -> 'key') = 'string' THEN option ->> 'key' ELSE '' END) = ''
                    OR btrim(CASE WHEN jsonb_typeof(option -> 'text') = 'string' THEN option ->> 'text' ELSE '' END) = ''
               )
               AND (SELECT count(*) = count(DISTINCT btrim(option ->> 'key')) FROM jsonb_array_elements(s.options) option)
               AND EXISTS (
                 SELECT 1 FROM jsonb_array_elements(s.options) option
                 WHERE btrim(option ->> 'key') = btrim(CASE WHEN jsonb_typeof(s.answer_json -> 'correctOption') = 'string' THEN s.answer_json ->> 'correctOption' ELSE '' END)
               )
               AND (s.rubric_json IS NULL OR s.rubric_json = 'null'::jsonb)
             WHEN 'FILL' THEN
               ((length(s.stem) - length(replace(s.stem, '{{blank}}', ''))) / 9) >= 1
               AND jsonb_typeof(s.answer_json -> 'blanks') = 'array'
               AND jsonb_array_length(s.blanks) = ((length(s.stem) - length(replace(s.stem, '{{blank}}', ''))) / 9)
               AND NOT EXISTS (
                 SELECT 1 FROM jsonb_array_elements(s.blanks) blank
                 WHERE jsonb_typeof(blank -> 'accepted') IS DISTINCT FROM 'array'
                    OR jsonb_array_length(CASE WHEN jsonb_typeof(blank -> 'accepted') = 'array' THEN blank -> 'accepted' ELSE '[]'::jsonb END) = 0
                    OR EXISTS (
                      SELECT 1
                      FROM jsonb_array_elements(CASE WHEN jsonb_typeof(blank -> 'accepted') = 'array' THEN blank -> 'accepted' ELSE '[]'::jsonb END) accepted
                      WHERE jsonb_typeof(accepted) <> 'string' OR btrim(accepted #>> '{}') = ''
                    )
                    OR (
                      SELECT count(*) <> count(DISTINCT btrim(accepted #>> '{}'))
                      FROM jsonb_array_elements(CASE WHEN jsonb_typeof(blank -> 'accepted') = 'array' THEN blank -> 'accepted' ELSE '[]'::jsonb END) accepted
                    )
               )
               AND (s.rubric_json IS NULL OR s.rubric_json = 'null'::jsonb)
             WHEN 'SHORT_ANSWER' THEN
               btrim(CASE WHEN jsonb_typeof(s.answer_json -> 'referenceAnswer') = 'string' THEN s.answer_json ->> 'referenceAnswer' ELSE '' END) <> ''
               AND jsonb_typeof(s.rubric_json) = 'object'
               AND jsonb_typeof(s.rubric_json -> 'items') = 'array'
               AND jsonb_array_length(s.rubric_items) > 0
               AND NOT EXISTS (
                 SELECT 1 FROM jsonb_array_elements(s.rubric_items) item
                 WHERE btrim(CASE WHEN jsonb_typeof(item -> 'criterion') = 'string' THEN item ->> 'criterion' ELSE '' END) = ''
                    OR NOT CASE WHEN jsonb_typeof(item -> 'score') = 'number' THEN (item ->> 'score')::numeric > 0 ELSE false END
               )
               AND (SELECT sum(CASE WHEN jsonb_typeof(item -> 'score') = 'number' THEN (item ->> 'score')::numeric ELSE 0 END) FROM jsonb_array_elements(s.rubric_items) item) = s.max_score
             WHEN 'ALGORITHM' THEN
               btrim(CASE WHEN jsonb_typeof(s.answer_json -> 'referenceAnswer') = 'string' THEN s.answer_json ->> 'referenceAnswer' ELSE '' END) <> ''
               AND jsonb_typeof(s.rubric_json) = 'object'
               AND jsonb_typeof(s.rubric_json -> 'items') = 'array'
               AND jsonb_array_length(s.rubric_items) > 0
               AND NOT EXISTS (
                 SELECT 1 FROM jsonb_array_elements(s.rubric_items) item
                 WHERE btrim(CASE WHEN jsonb_typeof(item -> 'criterion') = 'string' THEN item ->> 'criterion' ELSE '' END) = ''
                    OR NOT CASE WHEN jsonb_typeof(item -> 'score') = 'number' THEN (item ->> 'score')::numeric > 0 ELSE false END
               )
               AND (SELECT sum(CASE WHEN jsonb_typeof(item -> 'score') = 'number' THEN (item ->> 'score')::numeric ELSE 0 END) FROM jsonb_array_elements(s.rubric_items) item) = s.max_score
             ELSE false
           END
         ), false
       ) AS structurally_valid
FROM m04_question_shape s;

CREATE TEMP VIEW m04_eligible_question AS
SELECT a.*
FROM m04_question_audit a
JOIN rs_knowledge_point k ON k.id = a.knowledge_point_id AND k.subject_id = 1
JOIN rs_chapter c ON c.id = k.chapter_id AND c.subject_id = 1
WHERE a.status = 'ACTIVE'
  AND a.structurally_valid
  AND k.enabled
  AND c.enabled;

\echo '=== 1. 总题数 ==='
SELECT (SELECT count(*) FROM rs_question WHERE subject_id = 1) AS question_rows,
       (SELECT count(*) FROM m04_question_audit) AS questions_with_current_version,
       (SELECT count(*) FROM m04_eligible_question) AS m04_eligible_active_questions;

\echo '=== 2. 按 origin 与 status 分组（当前版本题） ==='
SELECT origin, status, count(*) AS question_count
FROM m04_question_audit
GROUP BY origin, status
ORDER BY origin, status;

\echo '=== 3. 按题型分组：全部当前版本 / M04 可组卷 ACTIVE ==='
SELECT types.type,
       count(a.question_id) AS all_current,
       count(e.question_id) AS eligible_active
FROM (VALUES ('CHOICE'), ('FILL'), ('SHORT_ANSWER'), ('ALGORITHM')) AS types(type)
LEFT JOIN m04_question_audit a ON a.type = types.type
LEFT JOIN m04_eligible_question e ON e.question_id = a.question_id
GROUP BY types.type
ORDER BY CASE types.type WHEN 'CHOICE' THEN 1 WHEN 'FILL' THEN 2 WHEN 'SHORT_ANSWER' THEN 3 ELSE 4 END;

\echo '=== 4. 有 M04 可组卷 ACTIVE 题的知识点数量 ==='
SELECT count(DISTINCT knowledge_point_id) AS knowledge_points_with_eligible_active_questions,
       (SELECT count(*) FROM rs_knowledge_point WHERE subject_id = 1) AS total_knowledge_points
FROM m04_eligible_question;

CREATE TEMP VIEW m04_kp_distribution AS
SELECT k.id AS knowledge_point_id,
       k.code,
       k.name,
       k.enabled AS knowledge_enabled,
       c.enabled AS chapter_enabled,
       count(e.question_id) AS total,
       count(e.question_id) FILTER (WHERE e.type = 'CHOICE') AS choice_count,
       count(e.question_id) FILTER (WHERE e.type = 'FILL') AS fill_count,
       count(e.question_id) FILTER (WHERE e.type = 'SHORT_ANSWER') AS short_answer_count,
       count(e.question_id) FILTER (WHERE e.type = 'ALGORITHM') AS algorithm_count
FROM rs_knowledge_point k
JOIN rs_chapter c ON c.id = k.chapter_id
LEFT JOIN m04_eligible_question e ON e.knowledge_point_id = k.id
WHERE k.subject_id = 1
GROUP BY k.id, k.code, k.name, k.enabled, c.enabled;

\echo '=== 5. 每个知识点的 M04 可组卷题量分布 ==='
SELECT code, name, knowledge_enabled, chapter_enabled,
       total, choice_count, fill_count, short_answer_count, algorithm_count
FROM m04_kp_distribution
ORDER BY code;

\echo '=== 6. 覆盖异常：缺绑定、字段不完整 ==='
SELECT count(*) FILTER (WHERE knowledge_point_id IS NULL) AS missing_binding_count,
       count(*) FILTER (WHERE NOT structurally_valid) AS incomplete_field_count
FROM m04_question_audit;

\echo '=== 7. M04 题库就绪门槛 ==='
-- 空库时 under_six=0、missing=0、incomplete=0 都是"空集为真"，
-- 不区分就会把 6 项显示成达标。这里加总题数前置于三态判定（达标/不达标/不适用）。
WITH metrics AS (
  SELECT
    (SELECT count(*) FROM m04_question_audit) AS total_questions,
    (SELECT count(DISTINCT knowledge_point_id) FROM m04_eligible_question) AS kp_with_questions,
    (SELECT count(*) FROM m04_eligible_question WHERE type = 'CHOICE') AS choice_count,
    (SELECT count(*) FROM m04_eligible_question WHERE type = 'FILL') AS fill_count,
    (SELECT count(*) FROM m04_eligible_question WHERE type = 'SHORT_ANSWER') AS short_count,
    (SELECT count(*) FROM m04_eligible_question WHERE type = 'ALGORITHM') AS algorithm_count,
    (SELECT count(*) FROM m04_question_audit WHERE knowledge_point_id IS NULL) AS missing_count,
    (SELECT count(*) FROM m04_question_audit WHERE NOT structurally_valid) AS incomplete_count,
    (SELECT count(*) FROM m04_kp_distribution WHERE total > 0 AND total < 6) AS under_six_kp_count
)
SELECT item, actual, required,
       CASE WHEN not_applicable THEN '不适用'
            WHEN passed THEN '达标'
            ELSE '不达标' END AS verdict
FROM metrics m
CROSS JOIN LATERAL (VALUES
  ('题库非空', m.total_questions, '>0', m.total_questions > 0, false),
  ('有题知识点', m.kp_with_questions, '>=15', m.kp_with_questions >= 15, m.total_questions = 0),
  ('每个有题知识点至少6道（不达标知识点数）', m.under_six_kp_count, '=0', m.under_six_kp_count = 0, m.total_questions = 0),
  ('单选题', m.choice_count, '>=40', m.choice_count >= 40, false),
  ('填空题', m.fill_count, '>=20', m.fill_count >= 20, false),
  ('简答/问答题', m.short_count, '>=10', m.short_count >= 10, false),
  ('算法设计题', m.algorithm_count, '>=10', m.algorithm_count >= 10, false),
  ('缺绑定题', m.missing_count, '=0', m.missing_count = 0, m.total_questions = 0),
  ('字段不完整题', m.incomplete_count, '=0', m.incomplete_count = 0, m.total_questions = 0)
) AS checks(item, actual, required, passed, not_applicable);

\echo '=== 8. 知识点缺口清单 ==='
SELECT code, name, total,
       greatest(6 - total, 0) AS needed_to_six,
       concat_ws(', ',
         CASE WHEN choice_count = 0 THEN 'CHOICE' END,
         CASE WHEN fill_count = 0 THEN 'FILL' END,
         CASE WHEN short_answer_count = 0 THEN 'SHORT_ANSWER' END,
         CASE WHEN algorithm_count = 0 THEN 'ALGORITHM' END
       ) AS missing_types,
       CASE
         WHEN NOT chapter_enabled THEN '章节停用'
         WHEN NOT knowledge_enabled THEN '知识点停用'
         WHEN total = 0 THEN '无可组卷 ACTIVE 题'
         WHEN total < 6 THEN '总题量不足 6'
         ELSE ''
       END AS gap_reason
FROM m04_kp_distribution
WHERE total < 6
   OR choice_count = 0
   OR fill_count = 0
   OR short_answer_count = 0
   OR algorithm_count = 0
ORDER BY code;

\echo '=== 9. 全局题型缺口 ==='
SELECT type, actual, target, greatest(target - actual, 0) AS missing
FROM (
  SELECT 'CHOICE' AS type, count(*)::bigint AS actual, 40::bigint AS target FROM m04_eligible_question WHERE type = 'CHOICE'
  UNION ALL SELECT 'FILL', count(*)::bigint, 20::bigint FROM m04_eligible_question WHERE type = 'FILL'
  UNION ALL SELECT 'SHORT_ANSWER', count(*)::bigint, 10::bigint FROM m04_eligible_question WHERE type = 'SHORT_ANSWER'
  UNION ALL SELECT 'ALGORITHM', count(*)::bigint, 10::bigint FROM m04_eligible_question WHERE type = 'ALGORITHM'
) gaps
ORDER BY CASE type WHEN 'CHOICE' THEN 1 WHEN 'FILL' THEN 2 WHEN 'SHORT_ANSWER' THEN 3 ELSE 4 END;

ROLLBACK;
