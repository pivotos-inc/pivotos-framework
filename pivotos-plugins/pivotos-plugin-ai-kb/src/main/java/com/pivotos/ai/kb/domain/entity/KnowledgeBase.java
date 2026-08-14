package com.pivotos.ai.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 知识库实体。
 * 一个知识库对应一种向量存储后端（simple / milvus / pgvector / qdrant），
 * 并维护默认的分块参数与 Embedding 模型偏好。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb_base")
public class KnowledgeBase extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 知识库名称 */
    private String name;

    /** 知识库描述 */
    private String description;

    /** 向量存储类型：simple / milvus / pgvector / qdrant */
    private String vectorStoreType;

    /** Embedding 模型标识（为空则使用系统默认） */
    private String embeddingModel;

    /** 默认分块大小（字符数） */
    private Integer chunkSize;

    /** 默认分块重叠（字符数） */
    private Integer chunkOverlap;

    /** 混合检索开关（true=向量+BM25+RRF, false=仅向量，默认 true） */
    private Boolean hybridSearch;

    /** 状态：0 正常，1 停用 */
    private Integer status;
}
