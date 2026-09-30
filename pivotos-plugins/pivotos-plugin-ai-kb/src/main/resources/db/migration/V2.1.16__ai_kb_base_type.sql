-- PivotOS ai-kb 插件 · A4E 审批建议增强（S117）：制度类标记
-- ai_kb_base 增 kb_type：把「制度库」从命名约定升级为契约字段。
-- 为什么不能靠 name 模糊匹配：dev 库现存「制度库-S101冒烟」这类命名是约定不是契约，
-- 而自动预审要求「必须有制度依据」是确定性判定——用枚举常量列而非自由文本，
-- 才能把「是否制度类」做成可单测锁死的规则，而不是字符串匹配。
-- 存量行默认 general（不破坏既有库），制度类需显式维护为 policy。

ALTER TABLE ai_kb_base
    ADD COLUMN kb_type VARCHAR(16) NOT NULL DEFAULT 'general' COMMENT '知识库类型（policy 制度类 / general 通用）' AFTER name,
    ADD KEY idx_kb_type (kb_type);
