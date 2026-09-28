package com.pivotos.ai.coding.modify;

import com.pivotos.ai.coding.config.ModifyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 修改型自动门禁（A4-2 / S111）。
 *
 * <p>两道闸：
 * <ol>
 *   <li><b>可应用性</b>：{@code git apply --check}，失败再以 {@code --recount} 复验。
 *       diff 由确定性渲染器产出，hunk 头计数本就准确，--recount 只是按 15 号口径
 *       保留的兜底（spike K1 ① 号死法的既有解药）。</li>
 *   <li><b>编译/类型检查</b>：落盘后跑（后端 mvn compile / 前端 pnpm typecheck）。
 *       这道闸是「文件外符号幻觉」的唯一自动拦截点——spike 全部语义失败案都靠编译失败暴露。</li>
 * </ol>
 *
 * <p>门禁二在落盘后执行，失败由 {@link ModifyApplyService} 还原原文（不依赖 git 工作区干净）。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@Component
public class ModifyGate {

    private static final Logger log = LoggerFactory.getLogger(ModifyGate.class);

    private final ModifyProperties properties;

    public ModifyGate(ModifyProperties properties) {
        this.properties = properties;
    }

    /**
     * 可应用性校验（不落盘）。
     */
    public ApplyCheck applyCheck(String diff, Path repoRoot) {
        if (diff == null || diff.isBlank()) {
            return new ApplyCheck(false, "diff 为空", false);
        }
        RunResult first = run(repoRoot, List.of("git", "apply", "--check", "-"), diff, null);
        if (first.exitCode() == 0) {
            return new ApplyCheck(true, "", false);
        }
        String rawError = first.output().isBlank() ? "git apply --check 失败" : first.output();
        RunResult recount = run(repoRoot, List.of("git", "apply", "--check", "--recount", "-"), diff, null);
        if (recount.exitCode() == 0) {
            log.warn("[AI Coding] diff 需 --recount 兜底：{}", rawError);
            return new ApplyCheck(true, rawError, true);
        }
        return new ApplyCheck(false, recount.output().isBlank() ? rawError : recount.output(), false);
    }

    /**
     * 落盘后编译/类型检查门禁。
     *
     * @param repoRoot      仓库根（pivotos-framework / pivotos-ui / pivotos-app）
     * @param relativePath  改动文件相对路径（决定走哪条命令）
     * @return 门禁结果；未启用或无可执行命令时 skipped=true
     */
    public BuildCheck build(Path repoRoot, String relativePath) {
        if (!properties.getGate().isBuildEnabled()) {
            return new BuildCheck(true, "编译门禁未启用", true);
        }
        Command command = resolveCommand(repoRoot, relativePath);
        if (command == null) {
            return new BuildCheck(true, "该仓库无对应门禁命令（移动端跳过）", true);
        }
        RunResult result = run(command.dir(), command.args(), null, command.env());
        if (result.exitCode() == 0) {
            return new BuildCheck(true, "", false);
        }
        String message = result.output().isBlank()
                ? "门禁命令退出码 " + result.exitCode()
                : result.output();
        return new BuildCheck(false, message, false);
    }

    /**
     * @param ok          可应用
     * @param message     失败原因（成功时为空；--recount 兜底成功时为原始报错）
     * @param recountUsed 是否靠 --recount 才通过
     */
    public record ApplyCheck(boolean ok, String message, boolean recountUsed) {
    }

    /**
     * @param ok      门禁通过
     * @param message 失败原因
     * @param skipped 是否跳过（未启用/无命令）
     */
    public record BuildCheck(boolean ok, String message, boolean skipped) {
    }

    /** 包内可见：门禁命令分派是「哪类仓走哪条命令」的易错点，须由 IT 直接断言（见 ModifyGateTest） */
    record Command(Path dir, List<String> args, Map<String, String> env) {
    }

    /**
     * 按改动归属解析门禁命令。
     *
     * <p><b>按仓库根判定而非路径前缀</b>：修改型的 {@code relativePath} 是仓内相对
     * （{@code apps/admin/src/...}），不含 {@code pivotos-ui/} 前缀——首轮按前缀判断会让
     * 前端改动掉进「无命令 → 跳过」分支，等于门禁静默失效。移动端仍返回 null（跳过）。
     */
    Command resolveCommand(Path repoRoot, String relativePath) {
        ModifyProperties.Gate gate = properties.getGate();
        String dir = CodingPathWhitelist.dirName(repoRoot);
        if (CodingPathWhitelist.UI_DIR.equals(dir)) {
            String template = gate.getUiCommand() == null || gate.getUiCommand().isBlank()
                    ? "pnpm typecheck"
                    : gate.getUiCommand();
            List<String> args = List.of("/bin/sh", "-c", template);
            // 踩坑 28：本机会注入 NODE_OPTIONS shim，须清空并固定 PATH 到 /usr/local/bin
            Map<String, String> env = Map.of("PATH", gate.getNodePath(), "NODE_OPTIONS", "");
            return new Command(repoRoot, args, env);
        }
        if (CodingPathWhitelist.APP_DIR.equals(dir)) {
            return null;
        }
        String module = CodingPathWhitelist.backendModuleOf(relativePath);
        if (module == null) {
            return null;
        }
        String template = (gate.getFwCommand() == null || gate.getFwCommand().isBlank()
                ? "mvn -o -q compile -pl {module} -DskipTests"
                : gate.getFwCommand()).replace("{module}", module);
        Map<String, String> javaEnv = Map.of("JAVA_HOME", defaultJavaHome());
        if (!template.startsWith("mvn")) {
            // 非 maven 形式的自定义命令（含测试用的必然失败桩）整体执行，不许再拼到 mavenPath 后面
            return new Command(repoRoot, List.of("/bin/sh", "-c", template), javaEnv);
        }
        List<String> args = new ArrayList<>();
        args.add(gate.getMavenPath());
        for (String token : template.split("\\s+")) {
            if (token.isBlank() || "mvn".equals(token)) {
                continue;
            }
            args.add(token);
        }
        return new Command(repoRoot, args, javaEnv);
    }

    private static String defaultJavaHome() {
        String home = System.getenv("JAVA_HOME");
        return home == null ? "/Library/Java/JavaVirtualMachines/jdk-25.jdk/Contents/Home" : home;
    }

    /**
     * 执行外部命令。
     *
     * @param dir    工作目录（仓库根）
     * @param args   命令与参数
     * @param stdin  标准输入（git apply 用）；null 表示不喂
     * @param env    额外环境变量（覆盖继承环境）
     */
    private RunResult run(Path dir, List<String> args, String stdin, Map<String, String> env) {
        try {
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.directory(dir == null ? null : dir.toFile());
            builder.redirectErrorStream(true);
            if (env != null && !env.isEmpty()) {
                builder.environment().putAll(env);
            }
            Process process = builder.start();
            if (stdin != null) {
                try (OutputStream out = process.getOutputStream()) {
                    out.write(stdin.getBytes(StandardCharsets.UTF_8));
                }
            } else {
                process.getOutputStream().close();
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(properties.getGate().getTimeoutSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new RunResult(-1, "门禁命令超时（" + properties.getGate().getTimeoutSeconds() + "s）");
            }
            return new RunResult(process.exitValue(), trim(output));
        } catch (IOException e) {
            return new RunResult(-1, "门禁命令执行失败：" + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new RunResult(-1, "门禁命令被中断");
        }
    }

    private static String trim(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value.length() > 800 ? value.substring(0, 800) + "…（截断）" : value;
    }

    private record RunResult(int exitCode, String output) {
    }
}
