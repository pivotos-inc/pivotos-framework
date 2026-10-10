package com.pivotos.starter.cloud.api.facade;

import com.pivotos.common.api.context.ContextFacade;

import java.util.List;

/**
 * Facade HTTP 白名单的<b>预置分组</b>（V3-S4 拍板口径）。
 *
 * <p><b>拍板结论：默认值仍只有 {@link ContextFacade}</b>（见
 * {@link FacadeHttpProperties#getInterfaces()}），本类里的分组<b>不会自动生效</b>，
 * 必须由部署方在 {@code pivotos.cloud.facade-http.interfaces} 里显式引用。
 * 理由：一旦默认值放开，只要有人把 {@code enabled} 打开，51 个方法（含
 * {@code generateToProject} / {@code approveTask} / {@code send} 这些写方法）就同时可用了 ——
 * 「默认形态零行为」这条边界不能靠「没人去开那个开关」来守。
 *
 * <p><b>为什么这些是字符串而不是 {@code IUserFacade.class.getName()}</b>：
 * 本模块是三通道契约包，<b>刻意不依赖任何 plugin 的 {@code *-api} 模块</b>
 * （否则「引入 starter 即拖进全部业务契约」，ArchUnit 的插件边界规则也会炸）。
 * 代价是类名写错编译期发现不了 —— 由 {@code FacadeHttpPresetCatalogTest}
 * （admin-server 侧，能看见全部插件）与启动期自检
 * {@link FacadeHttpWhitelist#inspect} 双重兜住。
 *
 * <p><b>{@code READ_ONLY_BUSINESS} 的准入口径</b>：只收「只读、无外部副作用」的方法。
 * 被刻意排除的方法（能力已具备，但需自行评估后显式开启）：
 * <ul>
 *   <li>{@code IMessageFacade#send/markRead/markAllRead} —— 落库 + 可能真发短信/邮件</li>
 *   <li>{@code IWorkflowFacade#startInstance/urgeInstance/approveTask} —— 发起流程 / 催办 / 审批通过</li>
 *   <li>{@code IGeneratorFacade#importTable/generateToProject/configureSubTable/configureTreeTable} —— 写库 / 写文件</li>
 *   <li>{@code IFileFacade#presignUpload/presignDownload} —— 产出私有桶限时 URL（越权下载面）</li>
 *   <li>{@code IAiFacade#chat/chatWithSystem/internalChat/dynamicChat} —— 真实 LLM 调用，有费用</li>
 *   <li>{@code ICodeIndexFacade#ensureIndex/rebuildIndex/invalidate} —— 全仓库扫描 / 缓存失效</li>
 * </ul>
 */
public final class FacadeHttpPresets {

    /**
     * 只读业务契约预置组（12 个接口 / 33 个方法）。
     *
     * <p>用法（命令行或环境变量二选一，逗号分隔）：
     * <pre>
     *   --pivotos.cloud.facade-http.interfaces=$(IFS=,; echo "${PRESET[*]}")
     *   PIVOTOS_CLOUD_FACADE_HTTP_INTERFACES=com.pivotos.system.api.facade.IUserFacade,...
     * </pre>
     *
     * <p><b>依赖登录态的成员需要同时开 {@code restore-login}</b>：
     * {@code IWorkflowFacade} 的 5 个方法与 {@code IMessageFacade#pageByUser/countUnread}
     * 语义上都是「当前登录用户的数据」，若不开 {@code context.restore-login=true}，
     * 它们会按「未登录」返回空页 —— <b>HTTP 200 + ok=true，但结果是错的</b>，
     * 这是本轮最难取证的点（见 FacadeHttpBusinessRoundTripTest 的对照断言）。
     */
    public static final List<String> READ_ONLY_BUSINESS = List.of(
        // ---- system-api（5 个，全只读）----
        "com.pivotos.system.api.facade.IDeptFacade",
        "com.pivotos.system.api.facade.IDictFacade",
        "com.pivotos.system.api.facade.IRoleFacade",
        "com.pivotos.system.api.facade.IStatsFacade",
        "com.pivotos.system.api.facade.IUserFacade",
        // ---- ai-kb-api（检索 / 统计，只读）----
        "com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade",
        // ---- file-api：只开纯计算与统计，不开预签名（越权下载面）----
        "com.pivotos.file.api.facade.IFileFacade:buildFileUrl;storageStats",
        // ---- ai-api：只开统计与模型解析，不开对话（真实 LLM 调用有费用）----
        "com.pivotos.ai.api.facade.IAiFacade:chatStats;resolveModel",
        // ---- generator-api：只开预览，不开写库/写文件 ----
        "com.pivotos.generator.service.IGeneratorFacade:previewCode;previewPluginSkeleton",
        // ---- migration-api：只开读，不开重建（全仓库扫描 IO）----
        "com.pivotos.migration.api.codeindex.ICodeIndexFacade:symbolTable;readFile;stats",
        // ---- workflow-api：只开读（依赖登录态）----
        "com.pivotos.workflow.api.IWorkflowFacade:countPending;instanceStats;"
            + "pagePendingTasks;pageMyInstances;getApprovalTaskContext",
        // ---- message-api：只开读（依赖登录态）----
        "com.pivotos.message.api.facade.IMessageFacade:pageByUser;countUnread"
    );

    /**
     * 把预置组拼成可直接写进环境变量 / 命令行参数的逗号分隔串。
     *
     * <p><b>条目之间逗号、方法之间分号</b>：{@code interfaces} 是 {@code List<String>}，
     * Spring 会把单个字符串按逗号切成多个条目，所以方法名只能用分号分隔
     * （详见 {@link FacadeHttpWhitelist} 的踩坑说明）。
     */
    public static String join(List<String> preset) {
        return String.join(",", preset);
    }

    private FacadeHttpPresets() {
    }
}
