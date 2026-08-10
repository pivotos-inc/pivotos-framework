package com.pivotos.file.storage;

/**
 * 对象存储策略 SPI（插件内部抽象，不进 -api：存储切换是实现细节，契约不感知）。
 * <p>职责边界：只做「对象键 → 存储操作」的翻译；
 * 对象键生成规则、扩展名白名单、历史 fileUrl 归一化等业务语义留在 FileService 层。
 * <p>实现按 {@code pivotos.file.storage-type} 条件装配二选一（minio / s3）。
 */
public interface StorageStrategy {

    /**
     * 存储类型标识（落 sys_file.storage_type：minio，或 s3 模式下的 vendor 标识 oss/cos/obs）
     */
    String storageType();

    /**
     * 生成预签名上传地址（PUT，有效期取配置 presignExpireSeconds）
     *
     * @param objectKey 对象键（业务层已生成）
     * @return 限时 PUT 直传 URL
     */
    String presignUpload(String objectKey);

    /**
     * 生成预签名下载地址（GET，有效期取配置 presignExpireSeconds）
     *
     * @param objectKey 对象键（业务层已归一化）
     * @return 限时 GET 访问 URL
     */
    String presignDownload(String objectKey);

    /**
     * 删除对象（对象不存在时幂等成功）
     */
    void delete(String objectKey);

    /**
     * 由对象键拼接可访问 URL（落库/回显用，非签名地址）
     */
    String buildUrl(String objectKey);
}
