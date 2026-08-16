package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI Token 用量记录实体（S92）
 *
 * <p>每次 ChatModel/EmbeddingModel 调用落一条：成功记真实 token 数，
 * 失败记 0（调用量仍可统计）；供应商未返回 usage 时记 0（口径「未计量」，不因计量阻断业务）。
 * tenant_id/user_id 由埋点层在调用线程快照后显式写入（流式回调线程无上下文）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_usage")
public class AiUsage extends TenantBaseDO {

    /** 调用用户 ID（未登录链路为 null） */
    private Long userId;

    /** 供应商 ID（静态兜底 client 为 null） */
    private Long providerId;

    /** 供应商编码冗余（聚合展示免 join；静态兜底记 static） */
    private String providerCode;

    /** API Key ID（静态兜底为 null） */
    private Long keyId;

    /** 本次实际使用模型名 */
    private String model;

    /** 业务场景（chat/rag/coding/chart/other，见 AiUsageContext） */
    private String scene;

    /** 调用类型（chat=对话模型, embedding=向量化模型） */
    private String callType;

    /** 提示词 token 数 */
    private Integer promptTokens;

    /** 生成 token 数 */
    private Integer completionTokens;

    /** 总 token 数 */
    private Integer totalTokens;

    /** 调用是否成功（0成功 1失败） */
    private Integer failed;
}
