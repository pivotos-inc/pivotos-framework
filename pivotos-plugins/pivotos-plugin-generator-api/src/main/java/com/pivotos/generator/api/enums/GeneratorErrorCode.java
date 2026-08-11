package com.pivotos.generator.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * Generator 域错误码（号段 6xxx）
 */
public enum GeneratorErrorCode implements ErrorCode {

    // ---------- 表导入 ----------
    GEN_TABLE_NOT_FOUND(6001, "生成表信息不存在"),
    GEN_TABLE_EXISTS(6002, "该表已在生成列表中"),
    GEN_TABLE_IMPORT_FAILED(6003, "表结构导入失败"),
    GEN_COLUMN_QUERY_FAILED(6004, "字段信息查询失败"),
    GEN_TABLE_LIST_FAILED(6005, "表列表查询失败"),
    GEN_SUB_TABLE_NOT_FOUND(6006, "子表未导入生成器或子表外键列不存在"),
    GEN_TREE_CONFIG_MISSING(6007, "树表配置缺失或树字段不在表字段中"),
    // ---------- 代码生成 ----------
    GEN_CODE_FAILED(6101, "代码生成失败"),
    GEN_TEMPLATE_RENDER_FAILED(6102, "模板渲染失败"),
    GEN_TEMPLATE_NOT_FOUND(6103, "模板文件不存在"),
    GEN_PATH_INVALID(6104, "生成路径无效"),

    // ---------- 通用 ----------
    GEN_PARAM_INVALID(6000, "生成器参数无效"),
    ;

    private final int code;
    private final String msg;

    GeneratorErrorCode(int code, String msg) {
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
