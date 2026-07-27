package com.pivotos.server.arch;

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
 * PivotOS 架构规则集 A1-A7（A4 在 pivotos-common-core 单独执行，本类不再重复）。
 * 维护约定：S13 新增 Plugin 时，在 FUTURE_PLUGIN_IMPL 清单登记其实现包名，
 * A1/A2 由 a1_effective 一条规则同时覆盖（实现包只能依赖其他插件的 api 包，
 * 而 api 包不在排除清单内——其他插件实现包全部列入排除清单）。
 */
@AnalyzeClasses(packages = "com.pivotos", importOptions = ImportOption.DoNotIncludeTests.class)
class P0ArchitectureTest {

    /** 其他插件实现包清单（S13 新增 Plugin 登记；当前仅 system 一个插件，清单预登记未来插件） */
    private static final String[] OTHER_PLUGIN_IMPL = {
        "com.pivotos.message..", "com.pivotos.flow..",
        "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor.."};

    /** ScopedValue 上下文体系所在包：A6/A7 唯一豁免区 */
    private static final String CORE_PACKAGE = "com.pivotos.starter.core..";

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

    // ========== A3：Starter 不依赖 Plugin 任何包（实现 + api 均禁止） ==========
    @ArchTest
    static final ArchRule a3_starters_must_not_depend_on_plugins = noClasses()
        .that().resideInAPackage("com.pivotos.starter..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.pivotos.system..", "com.pivotos.message..", "com.pivotos.flow..",
            "com.pivotos.file..", "com.pivotos.job..", "com.pivotos.monitor..")
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
    @ArchTest
    static final ArchRule a7_no_direct_threadlocal = noFields()
        .that().haveRawType(ThreadLocal.class)
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
}
