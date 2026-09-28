package com.pivotos.ai.coding.api.constant;

import com.pivotos.common.core.enums.error.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * AI Coding 错误码（7xxx）
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Getter
@AllArgsConstructor
public enum CodingErrorCode implements ErrorCode {

    /** 自然语言描述为空 */
    CODING_DESC_EMPTY(7001, "自然语言描述不能为空"),

    /** LLM 意图解析失败 */
    CODING_INTENT_PARSE_FAILED(7002, "AI 意图解析失败，请尝试用更明确的业务描述"),

    /** 生成器出码失败 */
    CODING_GENERATE_FAILED(7003, "代码生成失败"),

    /** 会话不存在 */
    CODING_SESSION_NOT_FOUND(7004, "AI Coding 会话不存在"),

    /** 表名冲突 */
    CODING_TABLE_EXISTS(7005, "目标表已存在，请修改表名"),

    /** 插件名非法（格式/保留名冲突） */
    CODING_PLUGIN_NAME_INVALID(7006, "插件名非法或与既有模块冲突"),

    /** 插件目录已存在 */
    CODING_PLUGIN_EXISTS(7007, "目标插件目录已存在，请更换插件名"),

    /** 红线 lint 未通过 */
    CODING_LINT_FAILED(7008, "产物红线检查未通过，请查看检查报告"),

    /** 落盘路径越界 */
    CODING_PATH_REJECTED(7009, "产物路径越出白名单，已拒绝落盘"),

    /** 装配补丁失败 */
    CODING_ASSEMBLY_FAILED(7010, "装配补丁失败（pom/扫描登记），请检查工程文件"),

    /** 多表意图关系不闭合/标识符非法（S52 / 2.4-F5） */
    CODING_RELATION_INVALID(7011, "多表结构校验未通过（关系不闭合或命名非法），请调整业务描述"),

    /** 定位能力未开启（S110 A4-1） */
    CODING_LOCATE_DISABLED(7012, "代码定位能力未开启，请配置 pivotos.ai.coding.locate"),

    /** 目标仓库未登记（repos 中无此 name） */
    CODING_LOCATE_REPO_UNKNOWN(7013, "目标代码仓库未登记，请检查 locate.repos 配置"),

    /** 索引为空（根路径不可读或 include 未命中任何文件） */
    CODING_LOCATE_INDEX_EMPTY(7014, "代码索引为空，请检查仓库根路径与 include 通配"),

    /** 定位失败（LLM 调用或结果解析异常） */
    CODING_LOCATE_FAILED(7015, "代码定位失败，请重试或用更明确的业务描述"),

    ;

    private final int code;
    private final String msg;
}
