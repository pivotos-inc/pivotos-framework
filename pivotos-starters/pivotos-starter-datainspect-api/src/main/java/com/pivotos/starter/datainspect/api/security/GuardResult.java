package com.pivotos.starter.datainspect.api.security;

import lombok.Builder;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 闸门结果：通过则给出<b>改写后的最终语句</b>与透明化提示；拒绝则给出原因。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
public class GuardResult {

    private boolean passed;

    /** 最终可执行语句（已注入 LIMIT / 已追加租户条件） */
    private String normalizedSql;

    /** 本次实际生效的行数上限 */
    private int effectiveMaxRows;

    private String rejectCode;

    private String rejectMessage;

    private List<String> warnings;

    public static GuardResult reject(String code, String message) {
        return GuardResult.builder()
                .passed(false)
                .rejectCode(code)
                .rejectMessage(message)
                .warnings(new ArrayList<>())
                .build();
    }

    public static GuardResult pass(String normalizedSql, int effectiveMaxRows, List<String> warnings) {
        return GuardResult.builder()
                .passed(true)
                .normalizedSql(normalizedSql)
                .effectiveMaxRows(effectiveMaxRows)
                .warnings(warnings == null ? new ArrayList<>() : warnings)
                .build();
    }
}
