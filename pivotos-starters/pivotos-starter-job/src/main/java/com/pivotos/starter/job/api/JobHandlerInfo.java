package com.pivotos.starter.job.api;

/**
 * 已注册的任务 Handler 元数据。
 *
 * @param handler     XXL-Job handler 名（与 @XxlJob value 一致）
 * @param displayName 显示名（中文，供管理端下拉选择）
 * @author PivotOS
 * @since 2.10.0
 */
public record JobHandlerInfo(String handler, String displayName) {
}
