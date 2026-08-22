package com.pivotos.file.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * 直传完成回调登记请求（POST /file/register）。
 * md5 预留字段：浏览器端无原生 MD5 能力，前端暂不传。
 */
@Data
public class FileRegisterRequest {

    /** 对象键（presign 返回的 objectKey） */
    @NotBlank(message = "对象键不能为空")
    private String objectKey;

    /** 原始文件名 */
    @Schema(description = "原始文件名")
    private String originalName;

    /** 文件大小（字节） */
    @Schema(description = "文件大小（字节）")
    private Long fileSize;

    /** 内容 MD5（预留） */
    @Schema(description = "内容 MD5（预留）")
    private String md5;

    /** MIME 类型 */
    @Schema(description = "MIME 类型")
    private String contentType;
}
