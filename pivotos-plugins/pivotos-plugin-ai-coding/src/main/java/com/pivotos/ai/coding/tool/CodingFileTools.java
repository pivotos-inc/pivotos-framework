package com.pivotos.ai.coding.tool;

import com.pivotos.ai.coding.api.constant.CodingErrorCode;
import com.pivotos.ai.coding.config.LocateProperties;
import com.pivotos.ai.coding.config.ModifyProperties;
import com.pivotos.ai.coding.modify.CodingPathWhitelist;
import com.pivotos.ai.coding.modify.ModifyGate;
import com.pivotos.ai.api.enums.ToolType;
import com.pivotos.ai.api.tool.AiToolMeta;
import com.pivotos.ai.api.tool.ToolObjectContributor;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.codeindex.ICodeIndexFacade;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_NO_CHANGE;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_AMBIGUOUS;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_SEARCH_MISSING;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_GATE_BUILD_FAILED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_LOCATE_REPO_UNKNOWN;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_MODIFY_DISABLED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_MODIFY_FILE_UNREADABLE;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_PATH_REJECTED;

/**
 * 工程文件读写 AI 工具（A4-3 / S112）：把「读源码 / 改源码」包装成 A2 工具体系的一等公民。
 *
 * <p>三件事缺一不可，缺一件就等于给模型开了任意文件写入口：
 * <ol>
 *   <li><b>路径白名单</b>：一律按仓内相对路径走 {@link CodingPathWhitelist#allowsInRepo(Path, String)}。
 *       白名单本体是四仓父目录基准，而定位产出的是仓内相对路径——二者混用会让 UI 侧改动被误杀
 *       （S111 K1），因此此处只用 <code>allowsInRepo</code> 这一个入口，绝不手工拼接前缀。</li>
 *   <li><b>二次确认</b>：写操作标 {@code @AiToolMeta(type=WRITE, confirmRequired=true)}，
 *       由 {@code GuardedToolCallback} 在 confirm=false 时返回预检说明（NEED_CONFIRM 审计），
 *       confirm=true 才落真盘。方法签名必须显式声明 {@code boolean confirm}，否则 Spring AI
 *       的入参 Schema 校验会把协议约定的键挡掉（S98 实测定型）。</li>
 *   <li><b>审计</b>：成功/失败/拒绝/预检均由 {@code AiToolInvokeRecorder} 全量落
 *       {@code ai_tool_invoke}；同时经 {@code AiToolRegistrySynchronizer} 进
 *       {@code ai_tool} 注册闸，未登记即拒。</li>
 * </ol>
 *
 * <p>写操作落盘后仍要过 {@link ModifyGate} 编译/typecheck 门禁，失败原样还原
 * （内存备份，不依赖 git 工作区干净）——与评审页「通过」走的是同一道闸。
 *
 * @author PivotOS
 * @since 2.14.0（S112 A4-3）
 */
@Component
@RequiredArgsConstructor
public class CodingFileTools implements ToolObjectContributor {

    private static final Logger log = LoggerFactory.getLogger(CodingFileTools.class);

    /** 读工具单次返回行数上限（控制模型上下文体积） */
    private static final int MAX_READ_LINES = 2000;

    /** 读工具默认返回行数 */
    private static final int DEFAULT_READ_LINES = 400;

    private final ModifyProperties modifyProperties;
    private final LocateProperties locateProperties;
    private final ModifyGate gate;
    private final ObjectProvider<ICodeIndexFacade> indexFacadeProvider;

    /**
     * 只有当上方 {@code pivotos.ai.coding.modify.enabled=true} 时才登记本组工具。
     *
     * <p>理由：这是一组能写工程文件的工具，能力必须显式开启；未开启时不进
     * {@code ai_tool} 注册表，模型连工具列表都看不到（比「能看到但调用被拒」更早拦）。
     */
    @Override
    public Object[] toolObjects() {
        return modifyProperties.isEnabled() ? new Object[]{this} : new Object[0];
    }

    @Tool(name = "readCodeFile",
            description = "读取已登记代码仓库中的单个源码文件（只读）。repo 为仓库逻辑名（如 fw / ui），"
                    + "path 为仓库根相对路径（如 pivotos-plugins/pivotos-plugin-x/src/main/java/... ）。"
                    + "仅允许读取白名单内的源码目录；maxLines 控制返回行数，超出部分截断并在末尾标注总行数。")
    public String readCodeFile(
            @ToolParam(description = "仓库逻辑名，取自代码定位配置：fw / ui / app") String repo,
            @ToolParam(description = "仓库根相对路径，如 pivotos-ui/apps/admin/src/api/system/post.ts") String path,
            @ToolParam(description = "最多返回行数（默认 400，上限 2000）") int maxLines) {
        Path repoRoot = resolveRepoRoot(repo);
        // 读也要过同一道白名单：先闸门后 IO，路径不合格不能靠「读不到」蒙混过去
        if (!CodingPathWhitelist.allowsInRepo(repoRoot, path)) {
            log.warn("[AI Tool] readCodeFile path rejected: repo={}, path={}", repo, path);
            throw new ServiceException(CODING_PATH_REJECTED);
        }
        String content = readRelative(repo, repoRoot, path);
        List<String> lines = content.lines().toList();
        int limit = maxLines <= 0 ? DEFAULT_READ_LINES : Math.min(maxLines, MAX_READ_LINES);
        List<String> shown = lines.size() <= limit ? lines : lines.subList(0, limit);
        StringBuilder out = new StringBuilder();
        out.append("文件：").append(repo).append(":").append(path)
                .append("（共 ").append(lines.size()).append(" 行）\n");
        out.append(String.join("\n", shown));
        if (lines.size() > limit) {
            out.append("\n…（已截断，仅返回前 ").append(limit).append(" 行）");
        }
        return out.toString();
    }

    @Tool(name = "writeCodeFile",
            description = "对已登记代码仓库中的源码文件执行一次「搜索-替换」写入（写操作）。"
                    + "search 必须与目标文件现有内容逐字完全一致（含缩进与换行），且必须在文件中唯一命中；"
                    + "落盘后自动执行编译/类型检查门禁，不通过会原样还原。"
                    + "二次确认协议：confirm=false 仅预检不写入；请向用户复述文件、改动内容与影响，"
                    + "获得明确同意后以 confirm=true 重新调用才真实写入。")
    @AiToolMeta(type = ToolType.WRITE, confirmRequired = true)
    public String writeCodeFile(
            @ToolParam(description = "仓库逻辑名：fw / ui") String repo,
            @ToolParam(description = "仓库根相对路径，与 readCodeFile 同一基准") String path,
            @ToolParam(description = "待替换原文片段，必须与文件内容逐字一致且唯一命中") String search,
            @ToolParam(description = "替换后的新内容（留空表示删除该片段）") String replace,
            @ToolParam(description = "二次确认标记：true 真实写入，false 仅预检") boolean confirm) {
        if (!modifyProperties.isEnabled()) {
            throw new ServiceException(CODING_MODIFY_DISABLED);
        }
        Path repoRoot = resolveRepoRoot(repo);
        if (!CodingPathWhitelist.allowsInRepo(repoRoot, path)) {
            log.warn("[AI Tool] writeCodeFile path rejected: repo={}, path={}", repo, path);
            throw new ServiceException(CODING_PATH_REJECTED);
        }
        if (search == null || search.isBlank()) {
            throw new ServiceException(CODING_EDIT_SEARCH_MISSING);
        }
        String current = readRelative(repo, repoRoot, path);
        int occurrences = countOccurrences(current, search);
        if (occurrences == 0) {
            log.info("[AI Tool] writeCodeFile search missing: repo={}, path={}", repo, path);
            throw new ServiceException(CODING_EDIT_SEARCH_MISSING);
        }
        if (occurrences > 1) {
            log.info("[AI Tool] writeCodeFile search ambiguous: repo={}, path={}, count={}", repo, path, occurrences);
            throw new ServiceException(CODING_EDIT_SEARCH_AMBIGUOUS);
        }
        String modified = current.replace(search, replace == null ? "" : replace);
        if (modified.equals(current)) {
            throw new ServiceException(CODING_EDIT_NO_CHANGE);
        }

        Path target = repoRoot.resolve(path).normalize();
        if (!target.startsWith(repoRoot)) {
            throw new ServiceException(CODING_PATH_REJECTED);
        }
        try {
            Files.writeString(target, modified, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[AI Tool] writeCodeFile write failed: repo={}, path={}", repo, path, e);
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }
        // 写盘后索引失效：不刷新会让后续定位读到旧内容
        ICodeIndexFacade facade = indexFacadeProvider.getIfAvailable();
        if (facade != null) {
            facade.invalidate(repo);
        }

        ModifyGate.BuildCheck build = gate.build(repoRoot, path);
        if (!build.ok()) {
            try {
                Files.writeString(target, current, StandardCharsets.UTF_8);
            } catch (IOException e) {
                log.error("[AI Tool] writeCodeFile rollback failed, file left modified: {}", path, e);
            }
            throw new ServiceException(CODING_GATE_BUILD_FAILED);
        }
        int before = current.lines().toList().size();
        int after = modified.lines().toList().size();
        String gateNote = build.skipped()
                ? "门禁跳过（" + build.message() + "）"
                : "编译/类型检查门禁通过";
        log.info("[AI Tool] writeCodeFile applied: repo={}, path={}, skipped={}", repo, path, build.skipped());
        return "已写入 " + repo + ":" + path + "（" + before + " 行 → " + after + " 行），" + gateNote + "。";
    }

    /** 仓库根解析：未登记一律 7013（也顺带挡掉空/null 仓库名） */
    private Path resolveRepoRoot(String repo) {
        LocateProperties.RepoConfig config = locateProperties.repo(repo);
        if (repo == null || config == null || config.getRoot() == null || config.getRoot().isBlank()) {
            throw new ServiceException(CODING_LOCATE_REPO_UNKNOWN);
        }
        return Path.of(config.getRoot());
    }

    /** 读文件：先磁盘读，读不了再退化到索引门面（索引只覆盖 include 通配内的文件） */
    private String readRelative(String repo, Path repoRoot, String relativePath) {
        Path target = repoRoot.resolve(relativePath).normalize();
        if (!target.startsWith(repoRoot)) {
            throw new ServiceException(CODING_PATH_REJECTED);
        }
        try {
            if (Files.isRegularFile(target)) {
                return Files.readString(target, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            log.warn("[AI Tool] read file failed, fallback to index: repo={}, path={}, err={}",
                    repo, relativePath, e.getMessage());
        }
        ICodeIndexFacade facade = indexFacadeProvider.getIfAvailable();
        if (facade != null) {
            var content = facade.readFile(repo, relativePath);
            if (content != null && content.isPresent()) {
                return content.get();
            }
        }
        throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int idx = text.indexOf(needle, from);
            if (idx < 0) {
                return count;
            }
            count++;
            from = idx + needle.length();
        }
    }
}
