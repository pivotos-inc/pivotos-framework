package com.pivotos.ai.kb.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库新增/修改入参（通用字段）。
 */
@Data
public class KbBaseSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 知识库名称 */
    @NotBlank(message = "知识库名称不能为空")
    private String name;

    /** 知识库描述 */
    private String description;

    /** 向量存储类型：simple / milvus */
    @NotBlank(message = "向量存储类型不能为空")
    private String vectorStoreType;

    /** Embedding 模型标识（为空使用系统默认） */
    private String embeddingModel;

    /** 默认分块大小 */
    @NotNull(message = "分块大小不能为空")
    private Integer chunkSize;

    /** 默认分块重叠 */
    @NotNull(message = "分块重叠不能为空")
    private Integer chunkOverlap;

    /** 混合检索开关（true=向量+BM25+RRF, false=仅向量，默认 true） */
    private Boolean hybridSearch;

    /** 重排开关（true=RRF 融合后经 reranker 精排，默认 true，S65） */
    private Boolean rerank;

    /** 状态：0 正常，1 停用 */
    @NotNull(message = "状态不能为空")
    private Integer status;
}
