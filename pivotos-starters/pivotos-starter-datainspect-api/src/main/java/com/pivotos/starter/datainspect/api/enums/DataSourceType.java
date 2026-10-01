package com.pivotos.starter.datainspect.api.enums;

import java.util.Arrays;
import java.util.Locale;

/**
 * 数据监控组件类型。
 *
 * <p>与 {@code pivotos.datainspect.components} 无关——本能力是「多组件并存」而非「选一个生效」，
 * 因此枚举既用于 Inspector 自报身份，也用于前端路由参数 {@code component=}。
 *
 * <p>NEO4J / CLICKHOUSE / MONGODB / KAFKA / MQ 为扩展点占位：本轮不提供实现，
 * 未装配时在组件清单里以 {@code available=false} 出现，便于运维一眼看出「支持但未接入」。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public enum DataSourceType {

    MYSQL("mysql", "MySQL"),
    ES("es", "Elasticsearch"),
    REDIS("redis", "Redis"),

    // ---------- 扩展点占位（本轮不实现，只登记类型与文档说明） ----------
    NEO4J("neo4j", "Neo4j"),
    CLICKHOUSE("clickhouse", "ClickHouse"),
    MONGODB("mongodb", "MongoDB"),
    KAFKA("kafka", "Kafka"),
    MQ("mq", "消息队列"),
    ;

    private final String code;
    private final String label;

    DataSourceType(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /** 大小写/空格不敏感；未命中返回 null（由调用方决定降级策略，不在此处擅自兜底） */
    public static DataSourceType of(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.code.equals(normalized)).findFirst().orElse(null);
    }
}
