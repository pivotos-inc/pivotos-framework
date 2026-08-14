package com.pivotos.ai.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 知识库文本块实体（BM25 检索用）。
 *
 * <p>文档向量化时同步写入此表，检索时用于 BM25 关键词匹配。
 * 与 VectorStore 中的向量数据一一对应，通过 kb_id / doc_id 关联。
 *
 * <p>不继承 BaseDO：chunk 只会被创建和物理删除，不需要审计字段和逻辑删除。
 */
@Data
@TableName("ai_kb_chunk")
public class AiKbChunk implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（自增） */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 知识库ID */
    private Long kbId;

    /** 文档ID */
    private Long docId;

    /** 块序号（从 0 开始） */
    private Integer chunkIndex;

    /** 文本块原文 */
    private String content;

    /** 内容MD5（前100字，去重用） */
    private String contentHash;

    /** 租户ID（从知识库继承） */
    private Long tenantId;

    /** 创建时间 */
    private LocalDateTime createTime;
}
