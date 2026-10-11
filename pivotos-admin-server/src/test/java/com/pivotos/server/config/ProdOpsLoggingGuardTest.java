package com.pivotos.server.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生产日志口径的静态守卫（v3.1.0 生产化 · A3）。
 *
 * <p>守的是三条「改了能起服、也能跑到 Production 上，但迟早出事」的红线：
 * <ol>
 *   <li><b>prod 不许 debug / trace</b>：磁盘与 IO 放大一个数量级，且 Spring MVC trace 会把
 *       参数处理器细节（可能含敏感字段）写进日志；WARN/ERROR 也会被淹没。</li>
 *   <li><b>prod 必须有滚动文件 appender</b>：没有 logback 配置时 start 脚本的定点重定向就是
 *       一个无限增长的单文件，最终把磁盘写满。</li>
 *   <li><b>prod 不能双写控制台</b>：nohup 重定向 + 控制台 appender 会存两份同样的日志。</li>
 * </ol>
 *
 * <p>刻意纯读文件不用 YAML 解析器：这里要断言的是「原文形态」（是否出现某个 key、
 * prod 分支里引用了哪个 appender），解析完反而看不见这些。
 *
 * @author PivotOS Team
 * @since 3.0.0（v3.1.0 生产化 · A3）
 */
class ProdOpsLoggingGuardTest {

    private static final Path PROD_YML = Path.of("src/main/resources/application-prod.yml");
    private static final Path LOGBACK = Path.of("src/main/resources/logback-spring.xml");
    private static final Path APP_YML = Path.of("src/main/resources/application.yml");

    @Test
    void prodMustNotRunOnDebugOrTrace() throws IOException {
        List<String> lines = Files.readAllLines(PROD_YML, StandardCharsets.UTF_8);
        boolean inLogging = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#")) {
                continue;
            }
            if (line.equals("logging:")) {
                inLogging = true;
                continue;
            }
            if (inLogging && !line.startsWith(" ") && !line.isBlank()) {
                break; // 缩进归零 → logging 块结束
            }
            if (!inLogging) {
                continue;
            }
            String lower = line.toLowerCase();
            assertTrue(!lower.contains(": debug") && !lower.contains(": trace"),
                    "application-prod.yml 的 logging.level 里不得出现 debug/trace，实测行：" + line);
            assertFalse(line.contains("mvc.method.annotation"),
                    "生产不得把 Spring MVC 参数处理器开到 trace（会带出参数细节），实测行：" + line);
        }
        String content = Files.readString(PROD_YML, StandardCharsets.UTF_8);
        assertTrue(content.contains("com.pivotos: info"),
                "prod 的 com.pivotos 必须是 info（排障临时开 debug 请用环境变量覆盖）");
    }

    @Test
    void prodLogbackMustRollAndRetain() throws IOException {
        assertTrue(Files.exists(LOGBACK), "缺少 logback-spring.xml：prod 日志会退回无限增长的单文件");
        String xml = Files.readString(LOGBACK, StandardCharsets.UTF_8);

        assertTrue(xml.contains("SizeAndTimeBasedRollingPolicy"), "滚动策略必须是「按天 + 按大小」");
        assertTrue(xml.contains("maxFileSize"), "缺少单文件大小封顶");
        assertTrue(xml.contains("maxHistory"), "缺少保留天数");
        assertTrue(xml.contains("totalSizeCap"), "缺少总量封顶");
        assertTrue(xml.contains("%i.log.gz") || xml.contains(".log.gz"), "归档必须压缩，否则保留 30 天撑爆磁盘");

        // 滚动分支只许引用 ROLLING_FILE，不许再挂 CONSOLE（否则双写）
        int prodStart = xml.indexOf("<springProfile name=\"prod | test\">");
        assertTrue(prodStart > 0, "缺少滚动文件分支（应为 <springProfile name=\"prod | test\">）");
        int prodEnd = xml.indexOf("</springProfile>", prodStart);
        String prodBlock = xml.substring(prodStart, prodEnd);
        assertTrue(prodBlock.contains("appender-ref ref=\"ROLLING_FILE\""), "prod/test 必须挂滚动文件 appender");
        assertTrue(!prodBlock.contains("appender-ref ref=\"CONSOLE\""),
                "prod/test 不得同时挂 CONSOLE（双重写：logback 一份 + nohup 一份）");
    }

    /**
     * V3.1-D 遗留④ / C4-1：test 与 prod 共用滚动策略，dev 仍只写控制台。
     *
     * <p>守的是「改一行就翻车」的两条：① 把 test 从滚动分支里摘掉（长期跑的测试环境重新回到
     * 单文件写满磁盘）；② 控制台分支写成 {@code !prod}（test 会同时命中两块 → 双写）。
     * 前者只影响运维体感，后者会真的让磁盘双份，两条都必须钉死。
     */
    @Test
    void testProfileRollsLikeProdAndDevKeepsConsoleOnly() throws IOException {
        String xml = Files.readString(LOGBACK, StandardCharsets.UTF_8);

        assertTrue(xml.contains("<springProfile name=\"prod | test\">"),
                "test 必须纳入滚动分支（V3.1-D C4-1），否则长期跑的测试环境会退回无限增长的单文件");

        // 控制台分支必须与滚动分支互斥：XML 里 & 已转义为 &amp;
        assertTrue(xml.contains("<springProfile name=\"!prod &amp; !test\">"),
                "控制台分支必须是 !prod & !test；写成 !prod 会让 test 同时命中两块（滚动文件 + 控制台双写）");
    }

    @Test
    void logTunablesAreExposedForOverride() throws IOException {
        String yml = Files.readString(APP_YML, StandardCharsets.UTF_8);
        for (String key : List.of("max-file-size", "max-history", "total-size-cap", "dir")) {
            assertTrue(yml.contains(key),
                    "application.yml 的 pivotos.log 下必须暴露 %s（运维要能在不改代码的前提下调整）".formatted(key));
        }
        assertTrue(yml.contains("PIVOTOS_LOG_DIR"), "日志目录必须可由环境变量覆盖");
    }

    /**
     * V3.1-H · B1：WARN 及以上必须走独立的错误文件，且主文件不得保留副本。
     *
     * <p>为什么值得单独立一条守卫：这条配置的三处必须<b>成对</b>维护——错误文件的
     * {@code ThresholdFilter}（收 WARN 及以上）与主文件的两条 {@code LevelFilter}
     * （挡 WARN、挡 ERROR）。漏掉主文件里的任意一条，就会变成「错误文件与主文件各存一份同样的
     * WARN」，而 biz 异常是 WARN 级别且故障期数量最多，双倍落盘恰好落在最贵的那一段；
     * 反过来若只写了主文件的 DENY 却忘了错误文件，WARN/ERROR 就是<b>直接丢盘且毫无报错</b>。
     * 这两种形态应用都起得来、日志看着也在写，只能靠静态断言钉死。
     */
    @Test
    void errorLogIsSplitIntoOwnFileWithoutDuplication() throws IOException {
        String xml = Files.readString(LOGBACK, StandardCharsets.UTF_8);
        int prodStart = xml.indexOf("<springProfile name=\"prod | test\">");
        assertTrue(prodStart > 0, "缺少滚动分支");
        String prodBlock = xml.substring(prodStart, xml.indexOf("</springProfile>", prodStart));

        assertTrue(prodBlock.contains("appender-ref ref=\"ERROR_FILE\""),
                "prod/test 必须挂错误文件 appender（WARN 及以上单独落盘）");
        assertTrue(prodBlock.contains("-error.log"), "错误文件名必须带 -error 后缀，避免与主文件混淆");

        int errorAppender = prodBlock.indexOf("<appender name=\"ERROR_FILE\"");
        assertTrue(errorAppender > 0, "缺少 ERROR_FILE appender 定义");
        String errorBlock = prodBlock.substring(errorAppender,
                prodBlock.indexOf("</appender>", errorAppender));
        assertTrue(errorBlock.contains("ThresholdFilter"),
                "错误文件必须用 ThresholdFilter 收窄级别（否则与主文件完全一致，分文件没有意义）");
        assertTrue(errorBlock.contains("<level>WARN</level>"),
                "错误文件应收敛 WARN 及以上；改成只收 ERROR 会让业务异常（WARN）无处可查");
        assertTrue(errorBlock.contains("${ERROR_MAX_HISTORY}") && errorBlock.contains("${ERROR_TOTAL_SIZE_CAP}"),
                "错误文件的保留天数和总量封顶必须走独立变量（默认 60 天 / 1GB，比主文件保留更久）");

        // 反向：主文件必须把 WARN/ERROR 挡掉，否则磁盘上就是双份
        int rollingAppender = prodBlock.indexOf("<appender name=\"ROLLING_FILE\"");
        assertTrue(rollingAppender > 0, "缺少 ROLLING_FILE appender 定义");
        String rollingBlock = prodBlock.substring(rollingAppender,
                prodBlock.indexOf("</appender>", rollingAppender));
        for (String level : List.of("WARN", "ERROR")) {
            assertTrue(rollingBlock.contains("<level>" + level + "</level>"),
                    "主文件缺少对 " + level + " 的 LevelFilter：会导致该级别在主文件与错误文件双份落盘");
        }
        assertEquals(2, countMatches(rollingBlock, "<onMatch>DENY</onMatch>"),
                "主文件应恰好挡两个级别（WARN/ERROR）；多挡会丢日志，少挡会双写");
        assertTrue(rollingBlock.contains("LevelFilter"),
                "主文件必须用 LevelFilter 精确挡级别；ThresholdFilter 只能表达「>=」语义，做不到「低于某级别才收」");
    }

    @Test
    void errorLogRetentionIsExposedForOverride() throws IOException {
        String yml = Files.readString(APP_YML, StandardCharsets.UTF_8);
        for (String key : List.of("error-max-history", "error-total-size-cap")) {
            assertTrue(yml.contains(key),
                    "application.yml 的 pivotos.log 下必须暴露 %s".formatted(key));
        }
        assertTrue(yml.contains("PIVOTOS_LOG_ERROR_MAX_HISTORY")
                        && yml.contains("PIVOTOS_LOG_ERROR_TOTAL_SIZE_CAP"),
                "错误文件的保留策略必须可由环境变量覆盖（不同环境磁盘预算不同）");
    }

    private static int countMatches(String text, String token) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = text.indexOf(token, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + token.length();
        }
    }

    /**
     * 探活开关的默认态由 {@code OpsHealthProperties#enabled}（true，matchIfMissing）保证，
     * yml 里<strong>不</strong>写这一项——一旦写死，运维用环境变量关开关就会被 yml 顶回去
     * （Spring 的配置优先级：命令行 &gt; 环境变量 &gt; yml）。这里守住「不写死」这个约定。
     */
    @Test
    void healthProbeSwitchIsNotPinnedInYaml() throws IOException {
        String yml = Files.readString(APP_YML, StandardCharsets.UTF_8);
        assertFalse(yml.contains("pivotos.ops.health.enabled"),
                "不要把 enable 开关写死进 application.yml：默认开由代码默认值兜，写死会顶掉环境变量覆盖");
    }
}
