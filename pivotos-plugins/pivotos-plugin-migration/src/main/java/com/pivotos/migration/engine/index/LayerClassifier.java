package com.pivotos.migration.engine.index;

import com.pivotos.migration.api.codeindex.CodeLayer;

import java.util.Locale;

/**
 * 分层约定分类器：路径 + 源码特征 → {@link CodeLayer}。
 *
 * <p>存在的理由（S107 spike K2）：LLM 粗定位对上层文件（Controller / 接口 / DTO / 实体）的
 * 置信度系统性高于实现层，fw 侧 top1 命中 0/5。仲裁阶段必须有一个确定性信号把「业务改动默认
 * 落在实现层」这条工程约定灌回去，而该信号只能来自路径/源码特征，不能来自 LLM 自评。
 *
 * <p>判定顺序即优先级：先认实现层（Impl / facade 实现），再认入口层，最后按命名后缀归堆。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public final class LayerClassifier {

    private LayerClassifier() {
    }

    /**
     * 分类入口。
     *
     * @param relativePath 仓库根相对路径（统一为 {@code /} 分隔）
     * @param content      文件全文（可为空串，仅按路径判定）
     * @param typeName     主类型名（Java 类名 / Vue 组件名 / ts 文件名，可空）
     * @param isInterface  是否接口/纯声明（Java interface）
     * @return 分层标签
     */
    public static CodeLayer classify(String relativePath, String content, String typeName, boolean isInterface) {
        String p = relativePath == null ? "" : relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        String name = typeName == null ? "" : typeName;
        String lower = name.toLowerCase(Locale.ROOT);

        if (p.endsWith(".vue")) {
            return CodeLayer.VUE_PAGE;
        }
        if (p.endsWith(".ts") || p.endsWith(".tsx") || p.endsWith(".js") || p.endsWith(".jsx")) {
            return classifyFrontendTs(p);
        }

        // 1) 实现层优先：路径含 /impl/ 或 名字以 Impl 结尾（业务改动默认落点）
        if (p.contains("/impl/") || lower.endsWith("impl")) {
            return CodeLayer.SERVICE_IMPL;
        }
        // 2) Facade 实现（如 AiLocalFacade）：facade 包下的非接口类视为实现层
        if (p.contains("/facade/") && !isInterface) {
            return CodeLayer.SERVICE_IMPL;
        }
        // 3) 入口层
        if (p.contains("/controller/") || lower.endsWith("controller")) {
            return CodeLayer.CONTROLLER;
        }
        // 4) 契约层：接口（Service/Facade/Client/Registry 等）
        if (isInterface) {
            return CodeLayer.SERVICE_API;
        }
        // 5) 数据访问
        if (lower.endsWith("mapper") || lower.endsWith("repository") || lower.endsWith("dao")) {
            return CodeLayer.MAPPER;
        }
        // 6) 实体（注解优先于命名，避免 *Entity 之外的命名漏判）
        if (content != null && (content.contains("@Entity") || content.contains("@TableName"))) {
            return CodeLayer.ENTITY;
        }
        if (p.contains("/entity/") || p.contains("/domain/entity/") || lower.endsWith("entity") || lower.endsWith("do")) {
            return CodeLayer.ENTITY;
        }
        // 7) 出参/入参
        if (lower.endsWith("vo") || p.contains("/vo/")) {
            return CodeLayer.VO;
        }
        if (lower.endsWith("dto") || lower.endsWith("bo") || lower.endsWith("query")
                || lower.endsWith("request") || lower.endsWith("cmd") || p.contains("/dto/")
                || p.contains("/bo/") || p.contains("/query/")) {
            return CodeLayer.DTO;
        }
        // 8) 配置
        if (lower.endsWith("config") || lower.endsWith("properties") || p.contains("/config/")) {
            return CodeLayer.CONFIG;
        }
        // 9) 工具
        if (lower.endsWith("util") || lower.endsWith("utils") || lower.endsWith("helper")
                || p.contains("/util/")) {
            return CodeLayer.UTIL;
        }
        // 10) 切面/拦截
        if (lower.endsWith("aspect") || lower.endsWith("interceptor") || lower.endsWith("listener")
                || lower.endsWith("handler") || lower.endsWith("filter") || p.contains("/aspect/")
                || p.contains("/interceptor/")) {
            return CodeLayer.ASPECT;
        }
        return CodeLayer.UNKNOWN;
    }

    /** 前端 ts/js：api 目录归 TS_API，其余按目录特征细分 */
    private static CodeLayer classifyFrontendTs(String p) {
        if (p.contains("/api/") || p.endsWith("/api.ts")) {
            return CodeLayer.TS_API;
        }
        if (p.contains("/views/") || p.contains("/components/") || p.contains("/layouts/")) {
            return CodeLayer.VUE_PAGE;
        }
        return CodeLayer.TS_OTHER;
    }
}
