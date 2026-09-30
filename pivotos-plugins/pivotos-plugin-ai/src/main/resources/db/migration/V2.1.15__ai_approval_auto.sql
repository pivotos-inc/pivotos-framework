-- PivotOS ai 插件 · A4E 审批建议增强（S117）：受控自动预审留痕
-- ai_approval_advice 扩展三列：是否自动通过 / 判定原因 / 规则命中明细
-- 为什么必须留痕：自动通过是「AI 代审批人执行动作」，事后必须能回答
-- 「为什么这条被自动通过 / 为什么这条没被自动通过」，且必须是确定性记录而非 LLM 自述。
-- 开关默认关闭（pivotos.ai.approval.auto-approve.enabled=false），未开启时 auto_passed 恒为 0，
-- auto_decision_reason 记「未启用」等确定性原因，方便审计区分「没开」与「判了但没过」。

ALTER TABLE ai_approval_advice
    ADD COLUMN auto_passed          TINYINT      NOT NULL DEFAULT 0 COMMENT '是否受控自动通过（1 本次建议触发了自动通过 0 未通过）' AFTER conclusion,
    ADD COLUMN auto_decision_reason VARCHAR(500) NULL COMMENT '自动预审判定原因（未启用/未命中规则/命中规则明细）' AFTER auto_passed,
    ADD COLUMN auto_rule_hits       VARCHAR(500) NULL COMMENT '低风险规则命中明细（JSON 数组；未命中为空数组）' AFTER auto_decision_reason;
