package com.pivotos.ai.kb.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * 知识库文档上传入参。
 * 前端先走文件预签名直传，再将对象 key / 访问地址回写此处。
 */
@Data
public class KbDocUploadRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 知识库 ID */
    @NotNull(message = "知识库 ID 不能为空")
    private Long kbId;

    /** 原始文件名 */
    @NotBlank(message = "文件名不能为空")
    private String fileName;

    /** 文件访问 URL 或对象 key */
    @NotBlank(message = "文件地址不能为空")
    private String fileUrl;

    /** 文件 MIME 类型 */
    private String fileType;

    /** 文件大小（字节） */
    private Long fileSize;
}
