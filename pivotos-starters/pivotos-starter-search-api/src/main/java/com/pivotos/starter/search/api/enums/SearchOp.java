package com.pivotos.starter.search.api.enums;

/**
 * 搜索条件操作符。
 * <p>这是「确定性条件树」的操作符枚举：Lambda 门面只是糖，真正落到各 Provider 的是
 * （field, op, values）三元组，避免每个 Provider 各自猜测 Lambda 语义。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public enum SearchOp {

    /** 相等 */
    EQ,
    /** 不等 */
    NE,
    /** 大于 */
    GT,
    /** 大于等于 */
    GE,
    /** 小于 */
    LT,
    /** 小于等于 */
    LE,
    /** 全模糊（%v%） */
    LIKE,
    /** 左模糊（%v） */
    LIKE_LEFT,
    /** 右模糊（v%） */
    LIKE_RIGHT,
    /** 包含于 */
    IN,
    /** 不包含于 */
    NOT_IN,
    /** 区间闭合 [v0, v1] */
    BETWEEN,
    /** 为空 */
    IS_NULL,
    /** 不为空 */
    IS_NOT_NULL,
    /** 全文匹配（分词检索） */
    MATCH
}
