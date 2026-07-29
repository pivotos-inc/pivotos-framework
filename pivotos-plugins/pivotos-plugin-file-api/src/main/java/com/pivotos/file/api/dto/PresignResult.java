package com.pivotos.file.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 预签名直传结果：前端先 PUT uploadUrl 上传，再用 fileUrl 回显/落库。
 */
@Data
@AllArgsConstructor
public class PresignResult {

    /** 对象键（存储内路径，业务表只存它） */
    private String objectKey;

    /** 预签名上传地址（PUT，限时有效） */
    private String uploadUrl;

    /** 上传成功后的访问地址 */
    private String fileUrl;

    /** 签名有效期（秒） */
    private Integer expireSeconds;
}
