package com.pivotos.server.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * PivotOS 架构规则集 A1-A8（A4 在 pivotos-common-core 单独执行，本类不再重复）。
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
        "com.pivotos.docsync.."};

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
        "pivotos-plugin-docsync", "doc_sync_");

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
                    "com.pivotos.workflow..", "com.pivotos.docsync..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.ai.api..",
                    "com.pivotos.ai.kb.api..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..")))
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
                    "com.pivotos.ai.kb..", "com.pivotos.workflow..", "com.pivotos.docsync..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.ai.kb.api..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..")))
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
                    "com.pivotos.ai..", "com.pivotos.workflow..", "com.pivotos.docsync..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.file.api..", "com.pivotos.ai.api..",
                    "com.pivotos.ai.kb..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..")))
        .allowEmptyShould(true);

    // monitor 侧对称规则（S48 新增，S71 运营看板启用 api 依赖）：monitor 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_monitor_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.monitor..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.ai..",
                    "com.pivotos.workflow..", "com.pivotos.docsync..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..", "com.pivotos.ai.api..",
                    "com.pivotos.file.api..",
                    "com.pivotos.ai.kb.api..", "com.pivotos.workflow.api..",
                    "com.pivotos.docsync.api..")))
        .allowEmptyShould(true);

    // workflow 侧对称规则（S55 新增）：workflow 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_workflow_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.workflow..")
        .and().resideOutsideOfPackage("com.pivotos.workflow.api..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
                    "com.pivotos.ai..", "com.pivotos.docsync..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.ai.api..", "com.pivotos.ai.kb.api..",
                    "com.pivotos.docsync.api..")))
        .allowEmptyShould(true);

    // docsync 侧对称规则（S90 新增）：docsync 实现包只可依赖其他插件的 api 包
    @ArchTest
    static final ArchRule a1_a2_docsync_impl_must_not_depend_on_other_plugin_impls = noClasses()
        .that().resideInAPackage("com.pivotos.docsync..")
        .and().resideOutsideOfPackage("com.pivotos.docsync.api..")
        .should().dependOnClassesThat(
            JavaClass.Predicates.resideInAnyPackage(
                    "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
                    "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
                    "com.pivotos.ai..", "com.pivotos.workflow..", "com.pivotos.ai.kb..")
                .and(JavaClass.Predicates.resideOutsideOfPackages(
                    "com.pivotos.system.api..", "com.pivotos.message.api..",
                    "com.pivotos.ai.api..", "com.pivotos.ai.kb.api..",
                    "com.pivotos.workflow.api..", "com.pivotos.file.api..")))
        .allowEmptyShould(true);

    // ========== A3：Starter 不依赖 Plugin 任何包（实现 + api 均禁止） ==========    @ArchTest
    static final ArchRule a3_starters_must_not_depend_on_plugins = noClasses()
        .that().resideInAPackage("com.pivotos.starter..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
            "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..",
            "com.pivotos.ai..", "com.pivotos.workflow..", "com.pivotos.docsync..")
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
