package com.pivotos.ai.kb.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库列表/详情 VO。
 */
@Data
public class KnowledgeBaseVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private String name;
    private String description;
    private String vectorStoreType;
    private String embeddingModel;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Boolean hybridSearch;
    private Boolean rerank;
    private Boolean queryRewrite;
    private Integer status;
    private Long tenantId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 文档数量（统计） */
    private Long docCount;
}
