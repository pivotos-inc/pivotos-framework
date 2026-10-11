package com.pivotos.starter.job.retention;

/**
 * 一次留存清理的执行报告（同时用于日志与任务执行记录）。
 *
 * @param table          目标表名
 * @param retentionDays  生效的保留天数
 * @param dryRun         是否演练（未真正删除）
 * @param skipped        是否因总开关关闭而整轮跳过
 * @param tenants        本轮覆盖的租户数（全局表恒为 0）
 * @param matched        命中的待删总行数
 * @param deleted        实际删除行数
 * @param truncated      是否因单次上限而未删完（true = 还需下一轮）
 * @param durationMs     耗时
 * @author PivotOS
 * @since 3.0.0
 */
public record RetentionReport(String table,
                              int retentionDays,
                              boolean dryRun,
                              boolean skipped,
                              int tenants,
                              long matched,
                              long deleted,
                              boolean truncated,
                              long durationMs) {

    /** 供任务执行记录 / 日志使用的单行摘要。 */
    public String summary() {
        if (skipped) {
            return String.format("retention[%s] SKIPPED（总开关已关闭）", table);
        }
        return String.format(
                "retention[%s] days=%d dryRun=%s tenants=%d matched=%d deleted=%d truncated=%s duration=%dms",
                table, retentionDays, dryRun, tenants, matched, deleted, truncated, durationMs);
    }
}
