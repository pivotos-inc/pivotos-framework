-- AI 供应商模型池：免费额度轮转降本（V3.2-D）
--
-- 背景：阿里云百炼「新人免费额度」按【模型维度】独立发放（通常每模型 100 万 Token），
-- 官方明确「某个模型额度用完后系统不会自动切换到其他有额度的模型，需手动修改 model 参数」
-- （见 help.aliyun.com/zh/model-studio/new-free-quota）。
-- 因此把「一批可互替的模型」配成池，由应用层轮询分摊，吃满各自独立额度。
--
-- 连带把默认模型降档：qwen-plus 属中档偏贵，降为 qwen3.8-flash
-- （2026-08-27 起输入 0.8 元/百万、输出 2.7 元/百万），作为「池全耗尽」时的降级终点。

ALTER TABLE ai_provider
    ADD COLUMN model_pool VARCHAR(500) NULL COMMENT '对话模型池（英文逗号分隔，如 qwen3.8-flash,qwen3.7-flash）；为空则只用 default_model' AFTER default_model;

-- 默认供应商降级：便宜档位兜底（池全耗尽时落到它，保证服务不 403）
UPDATE ai_provider
SET default_model = 'qwen3.8-flash'
WHERE id = 1
  AND code = 'dashscope'
  AND default_model = 'qwen-plus';
