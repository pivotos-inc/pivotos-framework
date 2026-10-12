package com.pivotos.workflow;

import org.dromara.warm.flow.orm.entity.FlowForm;
import org.dromara.warm.flow.ui.controller.WarmFlowController;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * warm-flow 版本升级守卫（V3.1-E：1.8.7 → 1.8.9）。
 *
 * <p>为什么要有这个测试：warm-flow 是第三方 jar，它的实体、端点、传递依赖全在本仓之外。
 * 升版本时**没有任何编译期信号**告诉你「实体多了一列」「端点多了一个」「传递依赖把
 * mybatis-plus-extension 拉回 3.5.15」。本测试把 V3.1-E 取证的三条结论用断言钉死，
 * 下次再升版本时它会先红，逼你重做取证，而不是等生产上出
 * {@code Table 'xxx' doesn't exist} / {@code NoSuchMethodError} / 匿名端点漏出。
 *
 * <p>四条断言：
 * <ol>
 *   <li><b>W-1 版本真的生效</b>——classpath 上的 warm-flow 版本 == {@value #EXPECTED_WARM_FLOW_VERSION}。
 *       防「改了 {@code pivotos-dependencies} 但被别处仲裁/传递拉回旧版」的假升级。</li>
 *   <li><b>W-2 实体与迁移对齐</b>——{@code FlowForm} 反射字段转下划线后，与
 *       {@code V1.2.53__warm_flow_form.sql} 建出的列**集合相等**。
 *       升级若加列，这条会红 → 提示必须新增 Flyway 迁移（已应用的 V1.2.53 字节冻结，不许改写）。</li>
 *   <li><b>W-3 extension 仲裁仍有效</b>——反射断言
 *       {@code AbstractRepository#executeBatch(Collection, int, BiFunction)} 存在。
 *       这个重载是 mybatis-plus **3.5.17 才有的**（3.5.15 只有 {@code BiConsumer} 版），
 *       warm-flow 的 sb4-starter 直引 3.5.15（1.8.9 复核仍如此）。若仲裁失效，
 *       classpath 上只剩 3.5.15 → 凡走 {@code saveBatch} 的链路运行期
 *       {@link NoSuchMethodError}。</li>
 *   <li><b>W-4 匿名闸门前缀仍覆盖</b>——{@code WarmFlowController} 的类级
 *       {@code @RequestMapping} 仍是 {@code /warm-flow}。V3.1-C 的
 *       {@code AnonymousAccessGuardFilter} 是**前缀级**闸门，第三方改了前缀就会静默失效
 *       （第三方 jar 加不上 {@code @SaCheckLogin}），届时全部内置端点匿名漏出。</li>
 * </ol>
 *
 * <p>注意：这是纯单元测试（不启 Spring、不连库），跑在段一 IT 里，升级后第一时间给信号。
 */
class WarmFlowVersionGuardTest {

    /** 本仓登记生效的 warm-flow 版本；升版本时**必须**连同取证一起更新本常量 */
    private static final String EXPECTED_WARM_FLOW_VERSION = "1.8.9";

    /** V3.1-D 建 flow_form 的迁移；若将来补列，改成最新的那条迁移 */
    private static final String FLOW_FORM_MIGRATION = "db/migration/V1.2.53__warm_flow_form.sql";

    @Test
    void w1_warmFlowOnClasspath_isExpectedVersion() {
        String actual = readWarmFlowVersion();
        assertThat(actual)
                .as("classpath 上的 warm-flow 版本（期望 %s；若本条红，说明要么没升上去、"
                                + "要么已升到新版本——后者请先重做 V3.1-E 那套取证：javap 比 FlowForm 字段、"
                                + "CRC 比 controller 端点、看 sb4-starter pom 的 mybatis-plus 版本，"
                                + "再回来更新本常量）", EXPECTED_WARM_FLOW_VERSION)
                .isEqualTo(EXPECTED_WARM_FLOW_VERSION);
    }

    @Test
    void w2_flowFormEntity_matchesMigrationColumns() {
        Set<String> entityColumns = declaredFieldsOf(FlowForm.class).stream()
                .map(WarmFlowVersionGuardTest::camelToSnake)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> migrationColumns = parseMigrationColumns(FLOW_FORM_MIGRATION);

        assertThat(migrationColumns)
                .as("迁移 %s 建出的列", FLOW_FORM_MIGRATION)
                .isNotEmpty();
        assertThat(entityColumns)
                .as("FlowForm 实体字段（转下划线）与迁移列必须集合相等。"
                                + "若升级后实体多出字段，说明要新增 Flyway 迁移补列："
                                + "已应用的 V1.2.53 字节冻结，只能加新版本迁移，不许改写旧脚本。"
                                + "实体=%s，迁移=%s", entityColumns, migrationColumns)
                .containsExactlyInAnyOrderElementsOf(migrationColumns);
    }

    @Test
    void w3_mybatisPlusExtension_isArbitratedToStarterVersion() throws Exception {
        Class<?> repo = Class.forName("com.baomidou.mybatisplus.extension.repository.AbstractRepository");

        Method biFunctionOverload;
        try {
            biFunctionOverload = repo.getDeclaredMethod("executeBatch", Collection.class, int.class, BiFunction.class);
        } catch (NoSuchMethodException e) {
            throw new AssertionError("classpath 上的 com.baomidou:mybatis-plus-extension 不是 3.5.17+："
                    + "AbstractRepository 缺少 executeBatch(Collection, int, BiFunction) 这个 3.5.17 才有的重载"
                    + "（3.5.15 只有 BiConsumer 版）。这说明 pivotos-dependencies 里对 "
                    + "mybatis-plus-extension 的仲裁失效（warm-flow 的 sb4-starter 直引 3.5.15），"
                    + "凡走 saveBatch 的链路会运行期 NoSuchMethodError。", e);
        }
        assertThat(biFunctionOverload).isNotNull();
    }

    @Test
    void w4_warmFlowController_stillUnderGuardedPrefix() {
        RequestMapping mapping = WarmFlowController.class.getAnnotation(RequestMapping.class);
        assertThat(mapping).as("WarmFlowController 必须有类级 @RequestMapping").isNotNull();
        assertThat(mapping.value())
                .as("V3.1-C 的匿名访问闸门配置的是前缀 /warm-flow/**（前缀级，第三方 jar 加不上注解）。"
                        + "第三方改了 controller 前缀而闸门没跟着改 → 全部内置端点匿名漏出且无任何报错。")
                .containsExactly("/warm-flow");
    }

    // ==================== 工具 ====================

    private static String readWarmFlowVersion() {
        // 构件 jar 里一定有 META-INF/maven/<group>/<artifact>/pom.properties
        String[] candidates = {
                "META-INF/maven/org.dromara.warm/warm-flow-core/pom.properties",
                "META-INF/maven/org.dromara.warm/warm-flow-mybatis-plus-core/pom.properties",
                "META-INF/maven/org.dromara.warm/warm-flow-orm/pom.properties",
        };
        for (String res : candidates) {
            Properties props = new Properties();
            try (InputStream in = WarmFlowVersionGuardTest.class.getClassLoader().getResourceAsStream(res)) {
                if (in == null) {
                    continue;
                }
                props.load(new InputStreamReader(in, StandardCharsets.UTF_8));
                String version = props.getProperty("version");
                if (version != null && !version.isBlank()) {
                    return version.trim();
                }
            } catch (Exception ignore) {
                // 换下一个候选
            }
        }
        throw new AssertionError("classpath 上找不到 warm-flow 的 pom.properties，无法判定运行态版本（候选："
                + String.join(", ", candidates) + "）");
    }

    private static List<String> declaredFieldsOf(Class<?> type) {
        List<String> names = new ArrayList<>();
        for (Field f : type.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                continue;
            }
            names.add(f.getName());
        }
        return names;
    }

    /** 从 CREATE TABLE 语句里解析列名（按 ORDINAL_POSITION 顺序） */
    private static Set<String> parseMigrationColumns(String resource) {
        String sql;
        try (InputStream in = WarmFlowVersionGuardTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("迁移脚本 %s 必须在 classpath 上", resource).isNotNull();
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("读取迁移脚本失败：" + resource, e);
        }
        // 先剥掉注释行：本仓迁移脚本的头部注释里带 ASCII 括号（如 WarmFlowService.publishedForm()），
        // 不剥掉会让 indexOf('(') 落在注释上，解析出的列全是注释里的词——静默假绿。
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new StringReader(sql))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("--") || t.startsWith("/*") || t.startsWith("*")) {
                    continue;
                }
                sb.append(line).append('\n');
            }
        } catch (Exception e) {
            throw new AssertionError("读取迁移脚本失败：" + resource, e);
        }
        String ddl = sb.toString();

        int table = ddl.indexOf("CREATE TABLE");
        assertThat(table).as("迁移 %s 里找不到 CREATE TABLE", resource).isGreaterThan(-1);
        int start = ddl.indexOf('(', table);
        int end = ddl.lastIndexOf(')');
        assertThat(start).as("迁移 %s 里找不到 CREATE TABLE 的括号", resource).isGreaterThan(table);
        assertThat(end).isGreaterThan(start);

        Set<String> columns = new LinkedHashSet<>();
        String body = ddl.substring(start + 1, end);
        try (BufferedReader reader = new BufferedReader(new StringReader(body))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.startsWith("--") || t.startsWith("/*") || t.startsWith("*")) {
                    continue;
                }
                if (t.startsWith("PRIMARY KEY") || t.startsWith("UNIQUE") || t.startsWith("KEY")
                        || t.startsWith("CONSTRAINT") || t.startsWith("INDEX")) {
                    continue;
                }
                String column = t.split("[\\s(]+", 2)[0];
                if (column.matches("[a-z_][a-z0-9_]*")) {
                    columns.add(column);
                }
            }
        } catch (Exception e) {
            throw new AssertionError("解析迁移脚本失败：" + resource, e);
        }
        return columns;
    }

    private static String camelToSnake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
