package com.pivotos.ai.coding.service;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import com.pivotos.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.*;

/**
 * 单表 CRUD 产物应用器（S43 / 2.2-F13）。
 * <p>
 * 与 Plugin 骨架 apply 对称：lint 门禁 → 路径白名单 → 落盘（workspace 根推导）
 * → Flyway 版本号扫描分配（全仓取最大 +1，写入目标插件 db/migration）
 * → pages.json 幂等注册 pages-gen 分包页面。
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Service
public class CrudApplyService {

    private static final Logger log = LoggerFactory.getLogger(CrudApplyService.class);

    /** 产物路径白名单 */
    private static final Pattern PLUGIN_FILE_PATTERN = Pattern.compile(
            "^pivotos-plugins/pivotos-plugin-[a-z0-9-]+/src/main/(java|resources)/.+");
    private static final Pattern PC_FILE_PATTERN = Pattern.compile(
            "^pivotos-ui/apps/admin/src/(api|views)/.+");
    private static final Pattern APP_FILE_PATTERN = Pattern.compile(
            "^pivotos-app/src/(api|pages-gen)/.+");

    private static final Pattern FLYWAY_FILE_PATTERN = Pattern.compile("^V(.+)__.*\\.sql$");

    private final ArtifactLinter artifactLinter;
    private final AssemblyPatcher assemblyPatcher;
    private final ObjectMapper objectMapper;

    public CrudApplyService(ArtifactLinter artifactLinter,
                            AssemblyPatcher assemblyPatcher,
                            ObjectMapper objectMapper) {
        this.artifactLinter = artifactLinter;
        this.assemblyPatcher = assemblyPatcher;
        this.objectMapper = objectMapper;
    }

    /** 应用 CRUD 产物：lint → 白名单 → 落盘 → 迁移版本分配 → pages.json 注册 */
    public void apply(CodingSession session) {
        Map<String, String> files = parseGeneratedFiles(session.getGeneratedFilesJson());
        if (files.isEmpty()) {
            throw new ServiceException(CODING_GENERATE_FAILED);
        }

        // ① 红线 lint 门禁
        List<String> violations = artifactLinter.lint(files);
        if (!violations.isEmpty()) {
            log.warn("[AI Coding] CRUD apply rejected by lint: session={}, violations={}",
                    session.getId(), violations);
            throw new ServiceException(CODING_LINT_FAILED);
        }

        // ② 路径白名单 + 归类
        Path frameworkRoot = assemblyPatcher.resolveFrameworkRoot();
        Path workspaceRoot = frameworkRoot.getParent();
        String sqlContent = null;
        String pluginBase = null; // pivotos-plugins/pivotos-plugin-x
        List<Map.Entry<String, String>> frameworkFiles = new ArrayList<>();
        List<Map.Entry<String, String>> workspaceFiles = new ArrayList<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            if (path.contains("..")) {
                log.warn("[AI Coding] Path rejected: {}", path);
                throw new ServiceException(CODING_PATH_REJECTED);
            }
            if (path.startsWith("sql/")) {
                sqlContent = entry.getValue();
            } else if (PLUGIN_FILE_PATTERN.matcher(path).matches()) {
                frameworkFiles.add(entry);
                if (pluginBase == null) {
                    pluginBase = path.substring(0, path.indexOf("/src/main/"));
                }
            } else if (PC_FILE_PATTERN.matcher(path).matches() || APP_FILE_PATTERN.matcher(path).matches()) {
                workspaceFiles.add(entry);
            } else {
                log.warn("[AI Coding] Path rejected: {}", path);
                throw new ServiceException(CODING_PATH_REJECTED);
            }
        }

        // ③ 落盘
        try {
            for (Map.Entry<String, String> entry : frameworkFiles) {
                write(frameworkRoot, entry.getKey(), entry.getValue());
            }
            for (Map.Entry<String, String> entry : workspaceFiles) {
                write(workspaceRoot, entry.getKey(), entry.getValue());
            }
            // ④ Flyway 迁移：全仓扫描最大版本号 +1，写入目标插件 db/migration
            if (sqlContent != null && pluginBase != null) {
                String nextVersion = nextFlywayVersion(frameworkRoot);
                String migrationRel = pluginBase + "/src/main/resources/db/migration/V"
                        + nextVersion + "__gen_" + session.getTableName() + ".sql";
                write(frameworkRoot, migrationRel, sqlContent);
                log.info("[AI Coding] Flyway migration written: {}", migrationRel);
            }
        } catch (IOException e) {
            log.error("[AI Coding] Write CRUD files failed", e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }

        // ⑤ pages.json 幂等注册（有 uni 产物才注册）
        boolean hasUniPages = files.keySet().stream().anyMatch(k -> k.startsWith("pivotos-app/src/pages-gen/"));
        if (hasUniPages) {
            registerPagesJson(workspaceRoot, session.getModuleName(), session.getBusinessName(),
                    session.getFunctionName());
        }
        // ⑥ vite dev 代理按 API 前缀登记（admin + app 两份配置，幂等；
        //    S43 实测：新模块前缀未代理 → PC/H5 dev 均 404）
        boolean hasPcFiles = files.keySet().stream().anyMatch(k -> k.startsWith("pivotos-ui/apps/admin/src/"));
        if (hasPcFiles) {
            patchViteProxy(workspaceRoot, session.getModuleName(),
                    Path.of("pivotos-ui/apps/admin/vite.config.ts"), true);
        }
        if (hasUniPages) {
            patchViteProxy(workspaceRoot, session.getModuleName(),
                    Path.of("pivotos-app/vite.config.ts"), false);
        }
        log.info("[AI Coding] CRUD applied: session={}, table={}", session.getId(), session.getTableName());
    }

    // ==================== vite dev 代理补丁 ====================

    /** vite.config.ts proxy 段幂等插入 '/{module}' 条目（锚点 "proxy: {"，已含则跳过） */
    void patchViteProxy(Path workspaceRoot, String moduleName, Path configRel, boolean withBypass) {
        Path viteConfig = workspaceRoot.resolve(configRel);
        if (!Files.exists(viteConfig)) {
            log.warn("[AI Coding] vite.config.ts 不存在，跳过代理补丁: {}", viteConfig);
            return;
        }
        try {
            String content = Files.readString(viteConfig, StandardCharsets.UTF_8);
            String entryKey = "'/" + moduleName + "':";
            if (content.contains(entryKey)) {
                return;
            }
            int proxyIdx = content.indexOf("proxy: {");
            if (proxyIdx < 0) {
                log.warn("[AI Coding] {} 缺少 proxy 段，跳过代理补丁", configRel);
                return;
            }
            int insertAt = content.indexOf('\n', proxyIdx) + 1;
            String entry;
            if (withBypass) {
                entry = "        '/" + moduleName + "': {\n"
                        + "          target: env.VITE_API_BASE_URL || 'http://localhost:8080',\n"
                        + "          changeOrigin: true,\n"
                        + "          bypass(req) {\n"
                        + "            if (req.headers.accept?.includes('text/html')) {\n"
                        + "              return '/index.html';\n"
                        + "            }\n"
                        + "          },\n"
                        + "        },\n";
            } else {
                entry = "        '/" + moduleName + "': { target: apiTarget, changeOrigin: true },\n";
            }
            content = content.substring(0, insertAt) + entry + content.substring(insertAt);
            Files.writeString(viteConfig, content, StandardCharsets.UTF_8);
            log.info("[AI Coding] vite proxy 已登记: {} /{}", configRel, moduleName);
        } catch (IOException e) {
            log.error("[AI Coding] vite.config.ts 代理补丁失败", e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }
    }

    // ==================== Flyway 版本扫描 ====================

    /** 全仓扫描 db/migration 取最大版本，末段 +1（如 1.2.17 → 1.2.18） */
    String nextFlywayVersion(Path frameworkRoot) {
        List<int[]> versions = new ArrayList<>();
        Path pluginsDir = frameworkRoot.resolve("pivotos-plugins");
        if (!Files.isDirectory(pluginsDir)) {
            return "1.0.0";
        }
        try (Stream<Path> plugins = Files.list(pluginsDir)) {
            for (Path plugin : plugins.toList()) {
                Path migrationDir = plugin.resolve("src/main/resources/db/migration");
                if (!Files.isDirectory(migrationDir)) {
                    continue;
                }
                try (Stream<Path> sqlFiles = Files.list(migrationDir)) {
                    for (Path f : sqlFiles.toList()) {
                        Matcher m = FLYWAY_FILE_PATTERN.matcher(f.getFileName().toString());
                        if (m.matches()) {
                            versions.add(parseVersion(m.group(1)));
                        }
                    }
                }
            }
        } catch (IOException e) {
            log.warn("[AI Coding] Flyway 版本扫描失败，回落 1.0.0", e);
            return "1.0.0";
        }
        int[] max = versions.stream()
                .max(CrudApplyService::compareVersion)
                .orElse(new int[]{0, 9, 0});
        int[] next = max.clone();
        next[next.length - 1] += 1;
        return joinVersion(next);
    }

    private static int[] parseVersion(String v) {
        String[] parts = v.split("[._]");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                nums[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                nums[i] = 0;
            }
        }
        return nums;
    }

    private static int compareVersion(int[] a, int[] b) {
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Comparator.comparingInt((Integer n) -> n).compare(x, y);
            }
        }
        return 0;
    }

    private static String joinVersion(int[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(v[i]);
        }
        return sb.toString();
    }

    // ==================== pages.json 注册 ====================

    /** 在 pages-gen 分包幂等注册 list/form/detail 三页（文本锚点插入，已含则跳过） */
    void registerPagesJson(Path workspaceRoot, String moduleName, String businessName, String functionName) {
        Path pagesJson = workspaceRoot.resolve("pivotos-app/src/pages.json");
        if (!Files.exists(pagesJson)) {
            log.warn("[AI Coding] pages.json 不存在，跳过注册: {}", pagesJson);
            return;
        }
        try {
            String content = Files.readString(pagesJson, StandardCharsets.UTF_8);
            String basePath = moduleName + "/" + businessName;
            // 幂等：list 页已注册即视为全部注册
            if (content.contains("\"path\": \"" + basePath + "/list\"")) {
                return;
            }
            int rootIdx = content.indexOf("\"root\": \"pages-gen\"");
            if (rootIdx < 0) {
                log.warn("[AI Coding] pages.json 缺少 pages-gen 分包，跳过注册");
                return;
            }
            int pagesIdx = content.indexOf("\"pages\": [", rootIdx);
            if (pagesIdx < 0) {
                log.warn("[AI Coding] pages-gen 分包缺少 pages 数组，跳过注册");
                return;
            }
            int insertAt = content.indexOf('\n', pagesIdx) + 1;
            String title = functionName == null || functionName.isBlank() ? businessName : functionName;
            String block = buildPageEntry(basePath + "/list", title, true)
                    + buildPageEntry(basePath + "/form", title + "表单", true)
                    + buildPageEntry(basePath + "/detail", title + "详情", true);
            content = content.substring(0, insertAt) + block + content.substring(insertAt);
            Files.writeString(pagesJson, content, StandardCharsets.UTF_8);
            log.info("[AI Coding] pages.json 已注册: {}/list|form|detail", basePath);
        } catch (IOException e) {
            log.error("[AI Coding] pages.json 注册失败", e);
            throw new ServiceException(CODING_GENERATE_FAILED);
        }
    }

    private static String buildPageEntry(String path, String title, boolean trailingComma) {
        return "\t\t\t\t{\n"
                + "\t\t\t\t\t\"path\": \"" + path + "\",\n"
                + "\t\t\t\t\t\"style\": {\n"
                + "\t\t\t\t\t\t\"navigationBarTitleText\": \"" + title + "\"\n"
                + "\t\t\t\t\t}\n"
                + "\t\t\t\t}" + (trailingComma ? "," : "") + "\n";
    }

    // ==================== 工具 ====================

    private static void write(Path root, String relative, String content) throws IOException {
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new ServiceException(CODING_PATH_REJECTED);
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
    }

    private Map<String, String> parseGeneratedFiles(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            log.warn("[AI Coding] Failed to parse generated files JSON", e);
            return Map.of();
        }
    }
}
