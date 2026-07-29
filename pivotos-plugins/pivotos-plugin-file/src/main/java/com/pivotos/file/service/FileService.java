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

    /**
     * 为已存储对象生成预签名下载地址（GET，限时有效，用于私有桶回显）
     *
     * @param objectKeyOrUrl 对象键，或历史落库的完整 fileUrl
     */
    String presignDownload(String objectKeyOrUrl);
}
