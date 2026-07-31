package com.pivotos.file.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * file 域错误码（4xxx 段）
 *
 * <p>号段分配：
 * <ul>
 *   <li>4000-4019 文件上传/预签名</li>
 *   <li>4020-4039 存储配置</li>
 * </ul>
 */
public enum FileErrorCode implements ErrorCode {

    // ---------- 文件上传/预签名 ----------
    FILENAME_EMPTY(4001, "文件名不能为空"),
    FILE_TYPE_NOT_ALLOWED(4002, "文件类型不允许"),
    PRESIGN_FAILED(4003, "预签名生成失败"),
    FILE_KEY_EMPTY(4004, "文件标识不能为空"),
    FILE_NOT_FOUND(4005, "文件记录不存在"),
    FILE_DELETE_FAILED(4006, "文件删除失败"),

    // ---------- 存储配置 ----------
    FILE_STORAGE_NOT_CONFIGURED(4020, "文件存储未配置");

    private final int code;
    private final String msg;

    FileErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
