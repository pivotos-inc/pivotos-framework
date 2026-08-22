package com.pivotos.migration.domain.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 迁移执行阶段。
 */
@Getter
@AllArgsConstructor
public enum MigrationPhase {

    UPLOAD("UPLOAD", "上传"),
    PARSE("PARSE", "解析"),
    ANALYZE("ANALYZE", "分析"),
    PLAN("PLAN", "计划"),
    GENERATE("GENERATE", "生成"),
    SELF_TEST("SELF_TEST", "自测"),
    REVIEW("REVIEW", "评审"),
    ROLLBACK("ROLLBACK", "回滚"),
    COMPLETE("COMPLETE", "完成");

    private final String code;
    private final String desc;
}
