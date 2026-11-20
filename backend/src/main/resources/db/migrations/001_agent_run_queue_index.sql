-- 已有的 MySQL 数据库执行一次。新建库已在 qgents_schema.sql 中声明该索引。
ALTER TABLE agent_runs ADD INDEX idx_agent_run_queue (status, id);
