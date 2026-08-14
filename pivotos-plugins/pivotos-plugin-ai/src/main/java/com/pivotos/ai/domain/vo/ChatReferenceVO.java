package com.pivotos.ai.domain.vo;

import lombok.Data;

/** RAG 引用来源（检索命中的知识库文档片段） */
@Data
public class ChatReferenceVO {

    /** 来源文件名 */
    private String fileName;

    /** 命中的文本块内容（截取前 200 字用于展示） */
    private String content;

    /** 相似度分数（部分 VectorStore 实现可能不返回，为 null） */
    private Double score;
}
