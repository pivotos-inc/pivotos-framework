package com.pivotos.ai.kb.api.dto;

import lombok.Data;

/**
 * 知识库检索结果 DTO（跨 Plugin 契约，不依赖 Spring AI Document）。
 */
@Data
public class KbSearchResultDTO {

    /** 命中的文本块内容 */
    private String content;

    /** 相似度分数（部分 VectorStore 实现可能不返回，为 null） */
    private Double score;

    /** 来源文件名 */
    private String fileName;

    /** 向量通道排名（0 表示未命中，检索调试用） */
    private Integer vectorRank;

    /** BM25 通道排名（0 表示未命中，检索调试用） */
    private Integer bm25Rank;

    /** 重排相关性分数（null 表示未重排，S65 检索调试用） */
    private Double rerankScore;
}
