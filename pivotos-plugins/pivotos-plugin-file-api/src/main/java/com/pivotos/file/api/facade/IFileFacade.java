package com.pivotos.file.api.facade;

import com.pivotos.file.api.dto.PresignResult;

/**
 * 文件存储 Facade 契约：预签名直传（PUT 到对象存储）。
 * 一期只承诺直传链路，分片/秒传/本地存储实现后续版本扩展。
 */
public interface IFileFacade {

    /**
     * 为指定文件名生成预签名上传地址
     *
     * @param filename 原始文件名（仅用于提取扩展名与可读前缀）
     * @return 预签名结果（对象键 / 上传地址 / 访问地址 / 有效期）
     */
    PresignResult presignUpload(String filename);

    /**
     * 由对象键还原访问地址
     */
    String buildFileUrl(String objectKey);

    /**
     * 为已存储对象生成预签名下载地址（GET，限时有效）。
     * 适用于私有桶回显：前端拿到限时 URL 直接展示。
     *
     * @param objectKeyOrUrl 对象键，或历史落库的完整 fileUrl（统一归一化为对象键）
     * @return 预签名 GET 访问地址
     */
    String presignDownload(String objectKeyOrUrl);
}
