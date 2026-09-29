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

    /** 修改型能力未开启（S111 A4-2） */
    CODING_MODIFY_DISABLED(7016, "代码修改能力未开启，请配置 pivotos.ai.coding.modify"),

    /** 目标文件不可读（路径不合法 / 不在登记仓库 / 读取失败） */
    CODING_MODIFY_FILE_UNREADABLE(7017, "目标文件不可读，请检查定位结果或仓库配置"),

    /** edit 指令解析失败（LLM 输出不合 schema） */
    CODING_EDIT_PARSE_FAILED(7018, "编辑指令解析失败，请重试或用更明确的业务描述"),

    /** search 段在原文中不存在——存在性闸门（spike K1 根治点） */
    CODING_EDIT_SEARCH_MISSING(7019, "待替换片段在目标文件中不存在，已拒绝生成补丁"),

    /** search 段多处命中且未指定 occurrence——歧义闸门 */
    CODING_EDIT_SEARCH_AMBIGUOUS(7020, "待替换片段在目标文件中存在多处，无法确定唯一位置"),

    /** diff 未能通过 git apply --check（含 --recount 兜底后仍失败） */
    CODING_DIFF_CHECK_FAILED(7021, "补丁可应用性校验未通过，已拒绝落盘"),

    /** 编译/typecheck 门禁未通过（改码已回滚） */
    CODING_GATE_BUILD_FAILED(7022, "编译/类型检查门禁未通过，改动已回滚"),

    /** 会话状态不允许应用（仅 status=1 待评审可应用；S111 补齐的状态守卫，spike I4 真实诉求） */
    CODING_SESSION_STATUS_INVALID(7023, "会话状态不允许应用（仅待评审状态可应用）"),

    /**
     * edit 指令应用后与原文无差异（S111 实测 spike I4：LLM 给出 blocks=1 但 replace 与 search 同义，
     * 属「改了个寂寞」的模型行为，不是 schema 问题）。单列一码以免与 7018「解析失败」混淆——
     * 两者处置不同：7018 可重试同一描述，7024 必须让描述更具体或人工指定落点。
     */
    CODING_EDIT_NO_CHANGE(7024, "编辑指令未产生实际改动，请细化描述或指定改动位置"),

    ;

    private final int code;
    private final String msg;
}
