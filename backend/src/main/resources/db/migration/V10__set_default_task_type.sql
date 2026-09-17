ALTER TABLE task
    MODIFY COLUMN task_type VARCHAR(20) NOT NULL DEFAULT '开发任务' COMMENT '任务类型：前端开发/后端开发/测试/UI设计/开发任务';
