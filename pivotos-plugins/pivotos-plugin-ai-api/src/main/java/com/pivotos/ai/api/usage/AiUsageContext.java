package com.pivotos.ai.api.usage;

import java.util.function.Supplier;

/**
 * AI 用量场景上下文（S92 Token 计量）
 *
 * <p>计量埋点在 AiClientRegistry 的 ChatModel 包装层统一收敛，但「本次调用属于哪条业务链路」
 * 只有调用入口知道：各链路入口在调用前 {@link #setScene(String)}，finally 中 {@link #clear()}；
 * 埋点层读取当前场景落 ai_usage.scene。未设置时记 other。
 *
 * <p>放 ai-api 模块：monitor（图表链路）只依赖 ai-api 契约，也能标注场景。
 * ThreadLocal 而非 ScopedValue：场景设置需跨插件代码「先 set 后调」，
 * ScopedValue 重绑定要求包裹整个调用块，跨模块改造面大；用 try/finally 纪律保证清理。
 */
public final class AiUsageContext {

    /** 场景：对话（含流式） */
    public static final String SCENE_CHAT = "chat";
    /** 场景：RAG 链路（意图路由/查询改写/检索增强调用） */
    public static final String SCENE_RAG = "rag";
    /** 场景：AI Coding 意图解析 */
    public static final String SCENE_CODING = "coding";
    /** 场景：AI 图表生成 */
    public static final String SCENE_CHART = "chart";
    /** 场景：未标注（静态兜底 client、未埋点调用） */
    public static final String SCENE_OTHER = "other";

    private static final ThreadLocal<String> SCENE = new ThreadLocal<>();

    private AiUsageContext() {
    }

    /** 设置当前线程场景（调用入口使用，须配对 {@link #clear()}） */
    public static void setScene(String scene) {
        SCENE.set(scene);
    }

    /** 当前场景，未设置返回 other */
    public static String getScene() {
        String scene = SCENE.get();
        return scene == null ? SCENE_OTHER : scene;
    }

    /** 清理当前线程场景（finally 使用） */
    public static void clear() {
        SCENE.remove();
    }

    /** 以指定场景执行 supplier，结束恢复原值（嵌套调用安全，如 chat 内嵌 rag 路由） */
    public static <T> T callWithScene(String scene, Supplier<T> supplier) {
        String prev = SCENE.get();
        SCENE.set(scene);
        try {
            return supplier.get();
        } finally {
            if (prev == null) {
                SCENE.remove();
            } else {
                SCENE.set(prev);
            }
        }
    }
}
