package com.pivotos.migration.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 迁移评审动作。
 */
@Getter
@AllArgsConstructor
public enum MigrationReviewAction {

    PASS("PASS", "通过"),
    REJECT("REJECT", "驳回"),
    MODIFY("MODIFY", "修改后通过");

    private final String code;
    private final String desc;
}
