package com.pivotos.ai.kb.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 知识库文档实体。
 * 记录文档元数据、向量化状态与错误信息；具体的向量内容写入由 VectorStore 后端承载。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_kb_document")
public class KbDocument extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 所属知识库 ID */
    private Long kbId;

    /** 原始文件名 */
    private String fileName;

    /** 文件访问 URL 或对象 key */
    private String fileUrl;

    /** 文件 MIME 类型 */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 实际分块大小 */
    private Integer chunkSize;

    /** 实际分块重叠 */
    private Integer chunkOverlap;

    /**
     * 文档状态。
     * 0 待处理，1 向量化中，2 已完成，3 失败
     */
    private Integer status;

    /** 最近一次失败原因 */
    private String errorMsg;

    /** 已写入向量库的文本块数量 */
    private Integer vectorCount;
}
