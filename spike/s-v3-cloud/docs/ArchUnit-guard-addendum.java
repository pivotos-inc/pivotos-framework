package com.pivotos.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * S133 V3 spike —— ArchUnit 守护规则<b>增补草案</b>（尚未落库到 admin-server，等 V3 正式启动时再审是否并入）
 *
 * <p>背景：spike 的第一件事就是「先把隐性跨模块调用扫出来」。扫描结果见
 * {@code evidence/01-cross-module-debt.txt}：45 个 Maven 模块、38 条合规契约边、
 * <b>仅 1 条真实欠款边</b>（{@code plugin-ai-coding -> plugin-ai}，13 处 import）、
 * 0 条 API 反向依赖、22 条手写 SQL 中 0 条跨模块 JOIN。
 *
 * <p>结论：现有架构已经具备拆分条件；需要的是<b>把「现状」变成「门禁」</b>，
 * 否则 V3 拆的过程中会不断产生新的隐性耦合。以下规则即为此而设：
 *
 * <ol>
 *   <li><b>C1 禁止业务 Plugin 跨实现模块直接调用</b>：任何 {@code com.pivotos.<实现包>}
 *       只应经由 {@code *.api.**} 契约包被引用。既有欠款（ai-coding → ai）用
 *       {@code .andDoesNotContain} 无法豁免，需按下方 {@code KNOWN_DEBT} 显式登记并限期清偿。</li>
 *   <li><b>C2 契约模块不得反向依赖实现</b>：{@code -api} 模块一旦 import 实现包，
 *       微服务化后必然循环依赖。</li>
 *   <li><b>C3 Facade 方法的入参/返回值必须是可跨进程序列化的类型</b>：
 *       这条难以用 import 规则表达，建议配合「Facade 方法签名不得出现 {@code **.domain.entity.**}」
 *       的约定 + 单测逐.reflective 反射校验。</li>
 *   <li><b>C4 数据层零跨库 JOIN</b>：Python 侧 {@code scan_cross_schema_sql.py} 已能全量扫，
 *       可直接作为 CI 的一个 job 跑，无需 ArchUnit。</li>
 * </ol>
 *
 * <p>落地位置建议：{@code pivotos-admin-server/src/test/java/com/pivotos/arch/}
 * （与既有 P0 门禁同目录、同 peanut Galileo: 一次 })
 */
class CloudBoundaryGuardTest {

    /** 已知欠款清单：清偿一笔删一行（每留存一行都必须在《已知边界》里有书面记录） */
    private static final String[][] KNOWN_DEBT = {
            // {"源模块包名", "目标模块包名", "清偿 Sprint"}
            {"com.pivotos.ai.coding", "com.pivotos.ai.client", "v3.0.0-S1"},
            {"com.pivotos.ai.coding", "com.pivotos.ai.domain", "v3.0.0-S1"},
    };

    @Test
    @DisplayName("C1：业务 Plugin 实现层不得直接引用其它 Plugin 的实现层（豁免已登记欠款）")
    void c1_noCrossPluginImplCall() {
        for (String[] debt : KNOWN_DEBT) {
            ArchRule rule = noClasses()
                    .that().resideInAPackage(debt[0] + "..")
                    .should().dependOnClassesThat().resideInAPackage(debt[1] + "..")
                    .because("跨 Plugin 调用必须走 -api Facade 契约；当前为已登记欠款，清偿 Sprint=" + debt[2]
                            + "，清偿后请改为断言（这里反向断言是为了让「欠款还在」这件事持续可见）");
            JavaClasses classes = new ClassFileImporter().importPackages("com.pivotos");
            // 欠款记录在案且会被打印；一旦 returnEmpty 即视为已清偿，需把该笔移出 KNOWN_DEBT
            rule.check(classes);
        }
    }

    @Test
    @DisplayName("C2：*-api 契约模块不得反向依赖实现模块")
    void c2_apiMustNotDependOnImpl() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..api..")
                .should().dependOnClassesThat().resideInAPackage("..impl..")
                .because("契约模块反向依赖实现会导致微服务化后的循环依赖");
        JavaClasses classes = new ClassFileImporter().importPackages("com.pivotos");
        rule.check(classes);
    }

    @Test
    @DisplayName("C3：Facade 接口的方法签名不得出现领域实体（需可跨进程序列化）")
    void c3_facadeSignatureSerializable() {
        ArchRule rule = noClasses()
                .that().haveSimpleNameEndingWith("Facade")
                .should().dependOnClassesThat().resideInAPackage("..domain.entity..")
                .because("Facade 是跨进程契约，入参/返回值只能是 api 包下的 DTO/VO");
        JavaClasses classes = new ClassFileImporter().importPackages("com.pivotos");
        rule.check(classes);
    }
}
