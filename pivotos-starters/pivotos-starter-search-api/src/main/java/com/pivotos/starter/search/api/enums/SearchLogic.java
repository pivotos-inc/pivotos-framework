package com.pivotos.starter.search.api.enums;

/**
 * 条件连接符。
 * <p>语义：描述「本条件如何与前一个条件连接」。首个条件的逻辑位固定为 AND（无前驱），
 * 与 MyBatis-Plus / Easy-ES wrapper 的口径一致。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public enum SearchLogic {

    /** 与前一个条件取交集 */
    AND,
    /** 与前一个条件取并集 */
    OR
}
