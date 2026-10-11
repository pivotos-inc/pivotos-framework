package com.pivotos.starter.job.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 留存清理任务的<strong>源码级接线守卫</strong>（v3.1.0 P1-B1）。
 * <p>
 * 为什么需要源码扫描而不是只测模板：安全带写得再好，只要有人新增一个留存任务时
 * 绕过它（自己写裸 DELETE、或忘了登记本地兜底），整套保护就在这个任务上失效——
 * 而这类漏接在编译期、启动期、单测里<strong>零信号</strong>（与本项目 A11「漏鉴权零信号」同源）。
 * 故用「登记制 + 新增即红」的口径守住三条：
 * <ol>
 *   <li><b>R1</b> 留存任务必须同时注册「手动触发」与「本地兜底」两处，漏后者 = XXL-Job 不可达时永不执行；</li>
 *   <li><b>R2</b> 留存任务必须走 {@link RetentionCleanupTemplate}，不得自己裸删（否则四道安全带全丢）；</li>
 *   <li><b>R3</b> 留存删除必须调 {@code physicalDeleteByIds}：{@code BaseMapper#delete*} 走
 *       {@code @TableLogic} 只置 {@code deleted=1}，空间不回收，用了等于没清理；</li>
 *   <li><b>R4</b> 物理删除的 Mapper 方法必须是 {@code @Delete} 且 SQL 里不含 {@code UPDATE}。</li>
 * </ol>
 *
 * @author PivotOS
 * @since 3.0.0
 */
@DisplayName("Retention job wiring guard")
class RetentionJobWiringGuardTest {

    /** starter-job 位于 pivotos-starters/<模块>，故回退两级才是 pivotos-plugins */
    private static final Path PLUGINS_DIR = Path.of("..", "..", "pivotos-plugins");

    /** 取 @Delete 注解的首段 SQL 字面量（注解是多行字符串拼接，第一段足以判定语义） */
    private static final java.util.regex.Pattern DELETE_ANNOTATION =
            java.util.regex.Pattern.compile("@Delete\\(\\s*\"([^\"]+)\"");

    @Test
    @DisplayName("R0 扫描目录必须存在且能命中留存任务（防路径漂移导致规则假绿）")
    void r0_scanRootMustExistAndHit() throws IOException {
        assertThat(Files.isDirectory(PLUGINS_DIR))
                .as("扫描目录不存在：%s（相对路径以模块工作目录为基准）", PLUGINS_DIR.toAbsolutePath())
                .isTrue();
        assertThat(retentionJobFiles())
                .as("一个留存任务都没扫到——要么目录写错，要么任务被改名，两种都该红")
                .isNotEmpty();
    }

    @Test
    @DisplayName("R1 留存任务必须同时注册手动触发与本地兜底")
    void r1_mustRegisterBothRegistries() throws IOException {
        var violations = new TreeSet<String>();
        for (Path file : retentionJobFiles()) {
            String src = Files.readString(file);
            if (!src.contains("retentionRegistry.register(")) {
                violations.add(file + " 未登记 RetentionJobRegistry（XXL-Job 不可达时永不执行）");
            }
            if (!src.contains("registry.register(")) {
                violations.add(file + " 未登记 JobHandlerRegistry（管理端无法手动触发）");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("R2 留存任务必须走 RetentionCleanupTemplate，不得绕过安全带")
    void r2_mustUseTemplate() throws IOException {
        var violations = new TreeSet<String>();
        for (Path file : retentionJobFiles()) {
            String src = Files.readString(file);
            if (!src.contains("RetentionCleanupTemplate.")) {
                violations.add(file + " 未调用 RetentionCleanupTemplate——等于绕过保留天数硬下限/单次上限/dry-run");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("R3 留存删除必须走物理删除，不得用 BaseMapper 的逻辑删除")
    void r3_mustPhysicallyDelete() throws IOException {
        var violations = new TreeSet<String>();
        for (Path file : retentionJobFiles()) {
            String src = Files.readString(file);
            if (!src.contains("physicalDeleteByIds")) {
                violations.add(file + " 未调用 physicalDeleteByIds——BaseMapper#delete* 是 @TableLogic 软删，不回收空间");
            }
        }
        assertThat(violations).isEmpty();
    }

    @Test
    @DisplayName("R4 物理删除的 Mapper 方法必须是 @Delete 且不含 UPDATE")
    void r4_physicalDeleteMapperMustBeRealDelete() throws IOException {
        var violations = new TreeSet<String>();
        for (Path file : mapperFilesWithPhysicalDelete()) {
            String src = Files.readString(file);
            java.util.regex.Matcher m = DELETE_ANNOTATION.matcher(src);
            if (!m.find()) {
                violations.add(file + " 声明了 physicalDeleteByIds 却没有 @Delete 注解");
                continue;
            }
            // 只看注解里的首段 SQL 字面量：源码注释里出现 UPDATE 字样（说明「为什么不用软删」时会写到）
            // 不该被当成违规，否则规则会误报并逼人改注释措辞。
            if (!m.group(1).contains("DELETE FROM")) {
                violations.add(file + " 的 @Delete 首段不是 DELETE FROM —— 逻辑删除回潮");
            }
        }
        assertThat(mapperFilesWithPhysicalDelete())
                .as("扫不到任何物理删除 Mapper（规则自身失效）：Mapper 总数=%d，扫描根=%s",
                        allMapperFiles().size(), PLUGINS_DIR.toAbsolutePath())
                .isNotEmpty();
        assertThat(violations).isEmpty();
    }

    private static List<Path> allMapperFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(PLUGINS_DIR)) {
            return stream
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> p.getFileName().toString().endsWith("Mapper.java"))
                    .toList();
        }
    }

    /**
     * 留存任务类：必须是 **Job 类**（{@code *CleanupJob.java} 或 {@code *Retention*Job.java}）。
     * <p>
     * 判定必须收紧到「Job 类」，不能只写「名字含 Retention」——
     * {@code FlowHisTaskRetentionMapper} 这类同样含 Retention 的 Mapper 会被误判成任务，
     * 然后因为「没有 register」而红。规则自己产生假阳性，久了就会被当成噪音关掉。
     * 同理，非留存的巡检任务（AiApiKeyHealthCheckJob）本来就不该进这套规则。
     */
    private static List<Path> retentionJobFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(PLUGINS_DIR)) {
            return stream
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith("CleanupJob.java")
                                || (name.contains("Retention") && name.endsWith("Job.java"));
                    })
                    .toList();
        }
    }

    private static List<Path> mapperFilesWithPhysicalDelete() throws IOException {
        try (Stream<Path> stream = Files.walk(PLUGINS_DIR)) {
            return stream
                    .filter(p -> p.toString().contains("/src/main/"))
                    .filter(p -> p.getFileName().toString().endsWith("Mapper.java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("physicalDeleteByIds");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .toList();
        }
    }
}
