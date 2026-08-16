package com.pivotos.starter.job.client;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * XXL-Job admin pageList 返回结果。
 * <p>
 * 使用 {@link JsonObject} 作为列表元素类型，不依赖 fork 的 XxlJobInfo 类。
 *
 * @author PivotOS
 * @since 2.11.0
 */
public record XxlJobPageResult(boolean success, String msg, List<JsonObject> data, int total) {

    public static XxlJobPageResult ok(List<JsonObject> data, int total) {
        return new XxlJobPageResult(true, null, data, total);
    }

    public static XxlJobPageResult fail(String msg) {
        return new XxlJobPageResult(false, msg, List.of(), 0);
    }
}
