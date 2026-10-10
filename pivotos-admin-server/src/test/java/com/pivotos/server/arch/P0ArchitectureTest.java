package com.pivotos.server.arch;

import com.pivotos.ai.api.enums.ToolType;
import com.pivotos.ai.api.tool.AiToolMeta;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * PivotOS 架构规则集 A1-A9（A4 在 pivotos-common-core 单独执行，本类不再重复）。
 * 维护约定：S13 新增 Plugin 时，在 FUTURE_PLUGIN_IMPL 清单登记其实现包名，
 * A1/A2 由 a1_effective 一条规则同时覆盖（实现包只能依赖其他插件的 api 包，
 * 而 api 包不在排除清单内——其他插件实现包全部列入排除清单）。
 * 表前缀登记在 A8 白名单 TABLE_PREFIX_WHITELIST。
 */
@AnalyzeClasses(packages = "com.pivotos", importOptions = ImportOption.DoNotIncludeTests.class)
class P0ArchitectureTest {

    /** 其他插件实现包清单（S13 新增 Plugin 登记；message 已于 S13、ai 已于 S19 落地，flow/job/monitor 预登记，workflow 于 S55 落地，ai-kb 于 S58 落地，docsync 于 S90 落地） */
    private static final String[] OTHER_PLUGIN_IMPL = {
        "com.pivotos.message..", "com.pivotos.flow..",
        "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
        "com.pivotos.ai..", "com.pivotos.workflow..", "com.pivotos.ai.kb..",
        "com.pivotos.docsync..", "com.pivotos.migration..", "com.pivotos.mind.."};

    /** ScopedValue 上下文体系所在包：A6/A7 唯一豁免区 */
    private static final String CORE_PACKAGE = "com.pivotos.starter.core..";

    /** A8 表前缀白名单（《03 后端功能开发流程》阶段2 登记处：插件模块名 → 表前缀） */
    private static final java.util.Map<String, String> TABLE_PREFIX_WHITELIST = java.util.Map.of(
        "pivotos-plugin-system", "sys_",
        "pivotos-plugin-message", "msg_",
        "pivotos-plugin-ai", "ai_",
        "pivotos-plugin-file", "sys_",
        "pivotos-plugin-workflow", "flow_",
        "pivotos-plugin-ai-kb", "ai_kb_",
        "pivotos-plugin-monitor", "mn_",
        "pivotos-plugin-docsync", "doc_sync_",
        "pivotos-plugin-migration", "migration_",
        "pivotos-plugin-mind", "mind_");

    // ========== A1 + A2：Plugin 实现包之间无编译依赖；跨插件仅可访问对方 api 包 ==========
    // 直接否定式：system 实现包不得依赖任何"其他插件实现包"；其他插件 api 包不在清单内，天然放行。
    // 注意：不要用 onlyDependOnClassesThat().resideOutsideOfPackages(...) 表达本规则——
    //       该组合在 ArchUnit 1.4.2 下产生假阳性（89 条误报，S9 实测），此为实证后的正确写法。
    // S13 起 system 之外的新插件按同款规则各加一条。
    @ArchTest
    static final ArchRule a1_a2_plugin_impls_must_not_depend_on_each_other = noClasses()
        .that().resideInAPackage("com.pivotos.system..")
        .and().resideOutsideOfPackage("com.pivotos.system.api..")
        .should().dependOnClassesThat().resideInAnyPackage(OTHER_PLUGIN_IMPL)
        .allowEmptyShould(true);

    // message 侧对称规则（S13 新增）：message 实现包只可依赖其他插件的 api 包（当前 system-api 放行）
    @ArchTest
    static final ArchRule a1_a2_message_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.message..")
        .and().resideOutsideOfPackage("com.pivotos.message.api..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.flow..", "com.pivotos.file..",
                    "com.pivotos.job..", "com.pivotos.monitor..", "com.pivotos.ai..",
                    "com.pivotos.workflow..", "com.pivotos.docsync..", "com.pivotos.migration..",
                    "com.pivotos.mind..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.ai.api..",
                    "com.pivotos.ai.kb.api..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..", "com.pivotos.migration.api..",
                    "com.pivotos.mind.api..")))
        .allowEmptyShould(true);

    // ai 侧对称规则（S19 新增）：ai 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_ai_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.ai..")
        .and().resideOutsideOfPackage("com.pivotos.ai.api..")
        .and().resideOutsideOfPackage("com.pivotos.ai.kb..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
                    "com.pivotos.ai.kb..", "com.pivotos.workflow..", "com.pivotos.docsync..",
                    "com.pivotos.migration..", "com.pivotos.mind..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.ai.kb.api..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..", "com.pivotos.migration.api..",
                    "com.pivotos.mind.api..")))
        .allowEmptyShould(true);

    // ai-kb 侧对称规则（S58 新增）：ai-kb 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_ai_kb_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.ai.kb..")
        .and().resideOutsideOfPackage("com.pivotos.ai.kb.api..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
                    "com.pivotos.ai..", "com.pivotos.workflow..", "com.pivotos.docsync..",
                    "com.pivotos.migration..", "com.pivotos.mind..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.file.api..", "com.pivotos.ai.api..",
                    "com.pivotos.ai.kb..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..", "com.pivotos.migration.api..",
                    "com.pivotos.mind.api..")))
        .allowEmptyShould(true);

    // monitor 侧对称规则（S48 新增，S71 运营看板启用 api 依赖）：monitor 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_monitor_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.monitor..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.ai..",
                    "com.pivotos.workflow..", "com.pivotos.docsync..", "com.pivotos.migration..",
                    "com.pivotos.mind..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..", "com.pivotos.ai.api..",
                    "com.pivotos.file.api..",
                    "com.pivotos.ai.kb.api..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..", "com.pivotos.migration.api..",
                    "com.pivotos.mind.api..")))
        .allowEmptyShould(true);

    // workflow 侧对称规则（S55 新增，S92 补登 migration）：workflow 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_workflow_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.workflow..")
        .and().resideOutsideOfPackage("com.pivotos.workflow.api..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
                    "com.pivotos.ai..", "com.pivotos.docsync..", "com.pivotos.migration..",
                    "com.pivotos.mind..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.ai.api..", "com.pivotos.ai.kb.api..",
                    "com.pivotos.docsync.api..", "com.pivotos.migration.api..",
                    "com.pivotos.mind.api..")))
        .allowEmptyShould(true);

    // docsync 侧对称规则（S90 新增，S92 补登 migration）：docsync 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_docsync_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.docsync..")
        .and().resideOutsideOfPackage("com.pivotos.docsync.api..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
                    "com.pivotos.ai..", "com.pivotos.workflow..", "com.pivotos.ai.kb..",
                    "com.pivotos.migration..", "com.pivotos.mind..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.ai.api..", "com.pivotos.ai.kb.api..",
                    "com.pivotos.workflow.api..", "com.pivotos.file.api..",
                    "com.pivotos.migration.api..", "com.pivotos.mind.api..")))
        .allowEmptyShould(true);

    // migration 侧对称规则（S92 新增）：migration 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_migration_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.migration..")
        .and().resideOutsideOfPackage("com.pivotos.migration.api..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
                    "com.pivotos.ai..", "com.pivotos.workflow..", "com.pivotos.ai.kb..",
                    "com.pivotos.docsync..", "com.pivotos.mind..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.file.api..", "com.pivotos.ai.api..",
                    "com.pivotos.ai.kb.api..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..", "com.pivotos.generator.api..",
                    "com.pivotos.mind.api..")))
        .allowEmptyShould(true);

    // mind 侧对称规则：mind 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_mind_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.mind..")
        .and().resideOutsideOfPackage("com.pivotos.mind.api..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
                    "com.pivotos.ai..", "com.pivotos.workflow..", "com.pivotos.ai.kb..",
                    "com.pivotos.docsync..", "com.pivotos.migration..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.file.api..", "com.pivotos.ai.api..",
                    "com.pivotos.ai.kb.api..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..", "com.pivotos.migration.api..")))
        .allowEmptyShould(true);

    // ========== A3：Starter 不依赖 Plugin 任何包（实现 + api 均禁止） ==========    @ArchTest
    static final ArchRule a3_starters_must_not_depend_on_plugins = noClasses()
        .that().resideInAPackage("com.pivotos.starter..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
            "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
            "com.pivotos.ai..", "com.pivotos.workflow..", "com.pivotos.docsync..",
            "com.pivotos.migration..", "com.pivotos.mind..")
        .allowEmptyShould(true);

    // ========== A6：无 new Thread / 裸 CompletableFuture.supplyAsync（必须走 ContextExecutor） ==========
    @ArchTest
    static final ArchRule a6_no_raw_threads = noClasses()
        .that().resideOutsideOfPackage(CORE_PACKAGE)
        .should().callConstructor(Thread.class, Runnable.class)
        .orShould().callConstructor(Thread.class, String.class)
        .orShould().callConstructor(Thread.class, Runnable.class, String.class)
        .orShould().callMethod(CompletableFuture.class, "supplyAsync", Supplier.class)
        .orShould().callMethod(CompletableFuture.class, "supplyAsync", Supplier.class, Executor.class)
        .allowEmptyShould(true);

    // ========== A7：无 ThreadLocal 直接使用（必须经 core 封装的 ScopedValue 门面） ==========
    // 反向断言：ThreadLocal 类型字段只允许出现在 core 包内
    // 唯一豁免（S93 补登）：AiUsageContext.SCENE —— AI 用量场景标注需跨插件「先 set 后调」，
    // ScopedValue 重绑定要求包裹调用块、跨模块改造面大；该类以 try/finally 纪律保证清理，
    // 设计理由见 AiUsageContext 类注释（S92 引入，S93 补豁免登记）。
    @ArchTest
    static final ArchRule a7_no_direct_threadlocal = noFields()
        .that().haveRawType(ThreadLocal.class)
        .and().areNotDeclaredIn("com.pivotos.ai.api.usage.AiUsageContext")
        .should().beDeclaredInClassesThat().resideOutsideOfPackage(CORE_PACKAGE)
        .allowEmptyShould(true);

    // ========== A5：跨 Plugin SQL 联表禁止 ==========
    // S7 决策 1「Java 零 SQL」下不存在 Mapper XML，本规则防回潮：扫描所有 Plugin 模块源码资源目录。
    @Test
    void a5_no_mapper_xml_in_any_plugin() throws Exception {
        Path pluginsDir = Path.of("..", "pivotos-plugins");
        try (var stream = Files.walk(pluginsDir)) {
            var xmls = stream
                .filter(p -> p.toString().contains(File.separator + "src" + File.separator + "main"))
                .filter(p -> p.toString().endsWith(".xml"))
                .map(Path::toString)
                .toList();
            org.assertj.core.api.Assertions.assertThat(xmls)
                .as("Java 零 SQL 决策：Plugin 中不允许存在任何 XML（含 Mapper XML），发现: %s", xmls)
                .isEmpty();
        }
    }

    // ========== A9：AI 工具层规则（S99 补工具层） ==========

    // A9a：@Tool 方法只允许声明在专用 tool 包内——
    // 工具必须经 AiToolCallbackConfiguration 汇聚 + GuardedToolCallbackProvider
    // 包裹守卫（注册闸/白名单/二次确认/审计），散落他处的 @Tool 会绕过守卫装配。
    // S112 扩展口径：plugin-ai 的 ToolObjectContributor 扩展点允许下游插件在
    // **自己的 .tool 包**里声明 @Tool（如 ai-coding 的 com.pivotos.ai.coding.tool，
    // 工程文件读写工具），经扩展点登记后共享同一套守卫与审计——依赖方向仍是
    // 下游 → plugin-ai，守卫装配单点不破。故白名单 = 各插件的 .tool.. 包。
    @ArchTest
    static final ArchRule a9a_tool_methods_confined_to_ai_tool_package = noMethods()
        .that().areDeclaredInClassesThat().resideOutsideOfPackage("com.pivotos.ai.tool..")
        .and().areDeclaredInClassesThat().resideOutsideOfPackage("com.pivotos.ai.coding.tool..")
        .should().beAnnotatedWith(org.springframework.ai.tool.annotation.Tool.class)
        .allowEmptyShould(true);

    // A9b：写操作工具二次确认签名守卫——@AiToolMeta(type=WRITE, confirmRequired=true) 的
    // @Tool 方法必须显式声明 boolean confirm 参数（S98 K1 实测：Spring AI 按方法签名生成
    // JSON Schema 严格校验入参，confirm 不在签名内会被 Schema 校验拒绝，预检协议失效）。
    // ArchUnit JavaParameter 不暴露参数名，故以反射直校方法签名；
    // 先用 ArchUnit 筛出含 @AiToolMeta 方法的类再反射，避免全量 Class.forName。
    @ArchTest
    static void a9b_write_tools_must_declare_confirm_param(JavaClasses classes) throws Exception {
        java.util.Set<String> candidateClasses = new java.util.HashSet<>();
        for (JavaClass javaClass : classes) {
            boolean hasMeta = javaClass.getMethods().stream()
                    .anyMatch(m -> m.isAnnotatedWith(AiToolMeta.class));
            if (hasMeta) {
                candidateClasses.add(javaClass.getName());
            }
        }
        List<String> violations = new ArrayList<>();
        for (String className : candidateClasses) {
            Class<?> clazz = Class.forName(className);
            for (java.lang.reflect.Method method : clazz.getDeclaredMethods()) {
                AiToolMeta meta = method.getAnnotation(AiToolMeta.class);
                if (meta == null || meta.type() != ToolType.WRITE || !meta.confirmRequired()) {
                    continue;
                }
                java.lang.reflect.Parameter confirmParam = null;
                for (java.lang.reflect.Parameter p : method.getParameters()) {
                    if ("confirm".equals(p.getName())) {
                        confirmParam = p;
                        break;
                    }
                }
                if (confirmParam == null || confirmParam.getType() != boolean.class) {
                    violations.add(className + "#" + method.getName());
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(violations)
                .as("写操作工具（@AiToolMeta WRITE + confirmRequired）必须声明 boolean confirm 参数，违反: %s", violations)
                .isEmpty();
    }

    // ========== A10：SQL 红线「禁 last()」（L6 CI 扫描，S118 清偿） ==========
    // 背景：《记忆 §5 SQL 十条》明写「禁 last()」，但代码里一直有 8 处历史写法无人兜底，
    // CI 也没有任何扫描——红线只写在文档里等于没有。
    // 口径（比「一刀切失败」诚实）：存量 8 处登记在 ALLOWED_LAST_USAGES 并注明正当性，
    // 规则卡两件事：① 任何**新增**文件出现 last( 立即失败；② 已登记文件的出现次数必须
    // 与登记值一致（次数不符 = 有人新增或有人忘了同步，同样失败）——
    // 白名单由此不会烂尾：清理掉一处就必须同步删登记，否则规则红。
    private static final java.util.Map<String, Integer> ALLOWED_LAST_USAGES = java.util.Map.of(
        "pivotos-plugin-ai-kb/KbEvalServiceImpl", 1,          // 评测记录列表 LIMIT（数值常量）
        "pivotos-plugin-ai/AiApprovalAdviceServiceImpl", 1,   // 取最近一条建议 LIMIT 1
        "pivotos-plugin-system/UserLocalFacade", 1,           // 用户面下拉 LIMIT（数值常量）
        "pivotos-plugin-system/MiniAuthServiceImpl", 1,       // openid 精确取一条 LIMIT 1
        "pivotos-plugin-system/SocialUserServiceImpl", 1,     // 第三方账号精确取一条 LIMIT 1
        "pivotos-plugin-system/NoticeServiceImpl", 1,         // 公告置顶列表 LIMIT（Math.clamp 边界值）
        "pivotos-plugin-mind/MindTodoServiceImpl", 1,         // 个人待办 LIMIT（Math.min 边界值）
        "pivotos-plugin-mind/MindKnowledgeServiceImpl", 1);   // 个人知识 LIMIT（Math.min 边界值）

    @Test
    void a10_sql_last_fragment_forbidden_unless_registered() throws Exception {
        Path[] roots = {Path.of("..", "pivotos-plugins"), Path.of("..", "pivotos-starters")};
        var lastPattern = java.util.regex.Pattern.compile("\\.last\\s*\\(");
        java.util.Map<String, Integer> found = new java.util.TreeMap<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                for (Path javaFile : stream
                        .filter(p -> p.toString().contains(File.separator + "src" + File.separator + "main"))
                        .filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher = lastPattern.matcher(Files.readString(javaFile));
                    int count = 0;
                    while (matcher.find()) {
                        count++;
                    }
                    if (count > 0) {
                        String key = normalizeKey(javaFile);
                        found.put(key, found.getOrDefault(key, 0) + count);
                    }
                }
            }
        }
        // ② 已登记文件的次数必须与登记值一致
        for (var entry : ALLOWED_LAST_USAGES.entrySet()) {
            int actual = found.getOrDefault(entry.getKey(), 0);
            org.assertj.core.api.Assertions.assertThat(actual)
                .as("L6 白名单失效：%s 登记 %d 处 last(，实际 %d 处——清理后请同步删登记，新增则改走 Limit/pagination",
                        entry.getKey(), entry.getValue(), actual)
                .isEqualTo(entry.getValue());
        }
        // ① 未登记文件出现 last( 一律失败
        java.util.Set<String> unregistered = new java.util.TreeSet<>(found.keySet());
        unregistered.removeAll(ALLOWED_LAST_USAGES.keySet());
        org.assertj.core.api.Assertions.assertThat(unregistered)
            .as("SQL 红线：禁止新的 last( 用法（PIVOTOS SQL 十条·禁 last()），发现: %s", unregistered)
            .isEmpty();
    }

    /** 判定键 = 「模块名/类名」，使其不受包路径与主机绝对路径影响 */
    private static String normalizeKey(Path javaFile) {
        String path = javaFile.toString().replace("\\", "/");
        int moduleIdx = path.lastIndexOf("/pivotos-plugins/");
        if (moduleIdx < 0) {
            moduleIdx = path.lastIndexOf("/pivotos-starters/");
        }
        String tail = moduleIdx >= 0 ? path.substring(moduleIdx + 1) : path;
        String[] parts = tail.split("/");
        String className = parts.length > 0 ? parts[parts.length - 1].replace(".java", "") : tail;
        String module = parts.length > 1 ? parts[1] : "";
        return module + "/" + className;
    }

    // ========== C1~C4：V3「形态革命」（v3.0.0）契约门禁（S133 spike → V3-S1 落地） ==========
    //
    // 为什么不用 A1/A2 那套「逐插件手写对称规则」：S133 全仓扫描发现唯一一条隐性欠款边
    // （ai-coding → ai，13 处 import / 8 个类）之所以能潜伏至今，根因就是 A1/A2 靠手工登记，
    // 而 **ai-coding 从未登记** —— 规则对它根本不存在。登记制的失效模式是「漏登记 = 免检」，
    // 新插件一旦忘了登记就自动获得一张免罪符。
    // 因此 C1/C2 改为**由代码全量推导**：插件归属按最长包名匹配，契约包从 -api 模块推导，
    // 新增插件无需任何登记即被覆盖。

    /** 插件实现根包（最长匹配定归属；monitor / generator 无 -api 模块，整包视为实现区） */
    private static final String[] PLUGIN_ROOTS = {
        "com.pivotos.system", "com.pivotos.message", "com.pivotos.file",
        "com.pivotos.workflow", "com.pivotos.docsync", "com.pivotos.migration",
        "com.pivotos.mind", "com.pivotos.monitor", "com.pivotos.generator",
        "com.pivotos.ai.coding", "com.pivotos.ai.kb", "com.pivotos.ai"
    };

    /**
     * 契约包白名单：跨插件只允许依赖这些包。
     *
     * <p><b>为什么用精确包而不是前缀</b>：生成器的契约模块包名<b>不含 .api</b>
     * （{@code IGeneratorFacade} 在 {@code com.pivotos.generator.service}），而实现模块的类
     * 恰好在它的子包 {@code com.pivotos.generator.service.impl} 与 {@code ...service.GeneratorService}。
     * 这是全仓唯一的「同名包跨实现与契约」形态（无同名类，非缺陷），按前缀判定会把实现类误判成契约类
     * （首跑实测误报 263 处），因此这里按<b>精确包名</b>匹配，把 generator 的实现子包排除在外。
     */
    private static final java.util.Set<String> CONTRACT_PACKAGES = java.util.Set.of(
        "com.pivotos.system.api", "com.pivotos.message.api", "com.pivotos.file.api",
        "com.pivotos.ai.api", "com.pivotos.ai.kb.api", "com.pivotos.ai.coding.api",
        "com.pivotos.workflow.api", "com.pivotos.docsync.api", "com.pivotos.migration.api",
        "com.pivotos.mind.api", "com.pivotos.generator.api", "com.pivotos.generator.service");

    /** 契约包的<b>实现侧排除项</b>：包名在白名单前缀内，但类其实属于实现模块（见白名单注释） */
    private static final java.util.Set<String> CONTRACT_PACKAGE_EXCLUSIONS = java.util.Set.of(
        "com.pivotos.generator.service.impl");

    /**
     * 同名包里唯一属于<b>契约模块</b>的类：{@code com.pivotos.generator.service} 包下同时有
     * 实现模块的 {@code GeneratorService} 和契约模块的 {@code IGeneratorFacade}——包级判定到此为止，
     * 只能按类名精确区分（首跑实测：包级判定把 GeneratorService 误判为契约，误报 6 处）。
     */
    private static final String GENERATOR_CONTRACT_CLASS = "com.pivotos.generator.service.IGeneratorFacade";

    /** 最长匹配定位包所属插件根包；不属任何插件（starter / common / 第三方）返回 null */
    private static String pluginRootOf(String packageName) {
        String best = null;
        for (String root : PLUGIN_ROOTS) {
            if ((packageName + ".").startsWith(root + ".") && (best == null || root.length() > best.length())) {
                best = root;
            }
        }
        return best;
    }

    private static boolean isContractPackage(String packageName) {
        // 排除项优先：generator.service.impl 落在契约包前缀内，但它是实现模块
        for (String ex : CONTRACT_PACKAGE_EXCLUSIONS) {
            if ((packageName + ".").startsWith(ex + ".")) {
                return false;
            }
        }
        return CONTRACT_PACKAGES.stream()
            .anyMatch(p -> (packageName + ".").startsWith(p + "."));
    }

    /** 类级判定：在包级判定之上叠加同名包的类级例外（generator.service 只有 IGeneratorFacade 是契约） */
    private static boolean isContractClass(JavaClass javaClass) {
        if (!isContractPackage(javaClass.getPackageName())) {
            return false;
        }
        return !"com.pivotos.generator.service".equals(javaClass.getPackageName())
            || GENERATOR_CONTRACT_CLASS.equals(javaClass.getName());
    }

    // C1：插件实现层不得直接依赖<b>其它插件的实现层</b>（跨插件只能走 -api 契约）。
    // 同插件内部、依赖 starter/common/第三方、依赖契约包，三种情况均放行。
    @ArchTest
    static void c1_plugin_impl_must_not_depend_on_other_plugin_impl(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            String self = javaClass.getPackageName();
            String selfRoot = pluginRootOf(self);
            if (selfRoot == null || isContractClass(javaClass)) {
                continue;
            }
            javaClass.getDirectDependenciesFromSelf().forEach(dep -> {
                JavaClass target = dep.getTargetClass();
                String targetPkg = target.getPackageName();
                String targetRoot = pluginRootOf(targetPkg);
                if (targetRoot == null || targetRoot.equals(selfRoot) || isContractClass(target)) {
                    return;
                }
                violations.add(javaClass.getName() + " -> " + target.getName()
                    + "（" + selfRoot + " → " + targetRoot + " 实现层）");
            });
        }
        org.assertj.core.api.Assertions.assertThat(violations)
            .as("C1：跨 Plugin 调用必须走 -api 契约，不得直接依赖其它插件实现层。违反 %d 处：%s",
                violations.size(), violations.isEmpty() ? "无" : violations)
            .isEmpty();
    }

    // C2：契约包（*-api，含 generator.service）不得反向依赖任何插件实现层——
    // 反向依赖会让微服务化后出现循环依赖，且契约模块会因此被迫带上实现模块的全部传递依赖。
    @ArchTest
    static void c2_contract_must_not_depend_on_impl(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            String self = javaClass.getPackageName();
            if (!isContractClass(javaClass)) {
                continue;
            }
            javaClass.getDirectDependenciesFromSelf().forEach(dep -> {
                JavaClass target = dep.getTargetClass();
                String targetPkg = target.getPackageName();
                if (pluginRootOf(targetPkg) == null || isContractClass(target)) {
                    return;
                }
                violations.add(javaClass.getName() + " -> " + target.getName());
            });
        }
        org.assertj.core.api.Assertions.assertThat(violations)
            .as("C2：-api 契约模块不得反向依赖插件实现层（微服务化后必然循环依赖）。违反 %d 处：%s",
                violations.size(), violations.isEmpty() ? "无" : violations)
            .isEmpty();
    }

    // C3：Facade 是跨进程契约，方法签名（入参 + 返回值）不得出现领域实体——
    // 一旦出现，HTTP 通道就必须序列化整个实体图（连带懒加载代理与循环引用），且契约与实现同生共死。
    // 现状：14 个 Facade 接口零违规，故直接落硬门禁，不设白名单。
    @ArchTest
    static void c3_facade_signature_must_be_serializable(JavaClasses classes) {
        List<String> violations = new ArrayList<>();
        for (JavaClass javaClass : classes) {
            // 只约束<b>对外契约接口</b>：实现类（XxxLocalFacade）内部用什么类型是它自己的事，
            // 它由本插件持有、不跨进程传输；一旦约束实现类的私有方法，规则就从「契约纯洁」
            // 变成「禁止实现用实体」，那是另一条规则，不该混在这里（首跑实测误伤 3 处即为此）。
            if (!javaClass.isInterface() || !javaClass.getSimpleName().endsWith("Facade")) {
                continue;
            }
            for (var method : javaClass.getMethods()) {
                var types = new ArrayList<JavaClass>();
                types.add(method.getRawReturnType());
                types.addAll(method.getRawParameterTypes());
                for (JavaClass t : types) {
                    String pkg = t.getPackageName();
                    // .domain.. 下的任何类型（entity / vo / model）都不许进契约签名
                    if (pkg.contains(".domain.") || pkg.endsWith(".domain")) {
                        violations.add(javaClass.getSimpleName() + "#" + method.getName() + " 用了 " + t.getName());
                    }
                }
            }
        }
        org.assertj.core.api.Assertions.assertThat(violations)
            .as("C3：Facade 方法签名（含返回值）不得出现领域实体，只能用 api 包下的 DTO/VO。违反：%s",
                violations.isEmpty() ? "无" : violations)
            .isEmpty();
    }

    // C4：数据层零跨库 JOIN（Python 扫描，与 ArchUnit 分工：SQL 不在字节码里，ArchUnit 看不见）。
    // 诚实口径：python3 不可用时不假装通过，直接 skip 并打印原因（CI 镜像须预装 python3）。
    @Test
    void c4_no_cross_schema_join() throws Exception {
        Path script = Path.of("..", "scripts", "arch", "scan_cross_schema_sql.py");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.exists(script), "扫描脚本缺失：" + script);
        ProcessBuilder pb = new ProcessBuilder("python3", script.toAbsolutePath().normalize().toString());
        pb.directory(Path.of("..").toAbsolutePath().normalize().toFile());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int code = proc.waitFor();
        org.assertj.core.api.Assertions.assertThat(code)
            .as("C4：数据层跨库 JOIN 扫描失败（退出码 %d），输出：\n%s", code, output)
            .isZero();
    }

    // ========== A8：Plugin 表前缀白名单（《03》阶段2 登记卡点） ==========
    // 扫描各 Plugin 实体源码中的 @TableName 字面量，必须命中本插件登记的前缀。
    @Test
    void a8_plugin_table_prefix_registered() throws Exception {
        Path pluginsDir = Path.of("..", "pivotos-plugins");
        var tableNamePattern = java.util.regex.Pattern.compile("@TableName\\(\"([^\"]+)\"\\)");
        for (var entry : TABLE_PREFIX_WHITELIST.entrySet()) {
            Path javaDir = pluginsDir.resolve(entry.getKey())
                .resolve(Path.of("src", "main", "java"));
            if (!Files.isDirectory(javaDir)) {
                continue;
            }
            try (var stream = Files.walk(javaDir)) {
                for (Path javaFile : stream.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher = tableNamePattern.matcher(Files.readString(javaFile));
                    while (matcher.find()) {
                        org.assertj.core.api.Assertions.assertThat(matcher.group(1))
                            .as("插件 %s 的表必须使用前缀 %s（文件 %s）",
                                entry.getKey(), entry.getValue(), javaFile)
                            .startsWith(entry.getValue());
                    }
                }
            }
        }
    }
}
