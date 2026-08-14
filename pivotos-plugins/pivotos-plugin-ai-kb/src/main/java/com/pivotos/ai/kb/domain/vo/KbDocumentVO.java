package com.pivotos.ai.kb.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 知识库文档 VO。
 */
@Data
public class KbDocumentVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long id;
    private Long kbId;
    private String fileName;
    private String fileUrl;
    private String fileType;
    private Long fileSize;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private Integer status;
    private String errorMsg;
    private Integer vectorCount;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
