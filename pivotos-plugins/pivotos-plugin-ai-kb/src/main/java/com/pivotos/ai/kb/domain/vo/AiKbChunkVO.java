package com.pivotos.ai.kb.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库文本块 VO（分块查看/解析预览用）。
 */
@Data
public class AiKbChunkVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;

    /** 块序号（从 0 开始） */
    private Integer chunkIndex;

    /** 文本块原文 */
    private String content;

    /** 内容MD5（前100字，去重用） */
    private String contentHash;
}
