package com.pivotos.file.service;

import com.pivotos.file.api.dto.PresignResult;

/** 文件存储服务 */
public interface FileService {

    /**
     * 生成预签名直传地址
     *
     * @param filename 原始文件名（用于扩展名校验与对象键前缀）
     */
    PresignResult presignUpload(String filename);

    /**
     * 由对象键还原访问地址
     */
    String buildFileUrl(String objectKey);
}
