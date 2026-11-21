ALTER TABLE rs_answer_asset ALTER COLUMN practice_item_id DROP NOT NULL;

ALTER TABLE rs_answer_asset ADD COLUMN task_item_id BIGINT REFERENCES rs_task_item(id);

ALTER TABLE rs_answer_asset ADD CONSTRAINT ck_rs_answer_asset_owner CHECK ((practice_item_id IS NULL) <> (task_item_id IS NULL));

CREATE UNIQUE INDEX uq_rs_answer_asset_task_position ON rs_answer_asset(task_item_id, position) WHERE task_item_id IS NOT NULL;
CREATE INDEX rs_answer_asset_task_item_idx ON rs_answer_asset(task_item_id);
