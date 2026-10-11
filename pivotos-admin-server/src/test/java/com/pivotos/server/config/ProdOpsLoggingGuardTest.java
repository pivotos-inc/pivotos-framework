package com.pivotos.server.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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

        // prod 分支只许引用 ROLLING_FILE，不许再挂 CONSOLE（否则双写）
        int prodStart = xml.indexOf("<springProfile name=\"prod\">");
        assertTrue(prodStart > 0, "缺少 prod 分支");
        int prodEnd = xml.indexOf("</springProfile>", prodStart);
        String prodBlock = xml.substring(prodStart, prodEnd);
        assertTrue(prodBlock.contains("appender-ref ref=\"ROLLING_FILE\""), "prod 必须挂滚动文件 appender");
        assertTrue(!prodBlock.contains("appender-ref ref=\"CONSOLE\""),
                "prod 不得同时挂 CONSOLE（双重写：logback 一份 + nohup 一份）");
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
