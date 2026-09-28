package com.pivotos.ai.coding.modify;

import com.pivotos.ai.coding.api.dto.LocateResultVO;
import com.pivotos.ai.coding.config.LocateProperties;
import com.pivotos.ai.coding.config.ModifyProperties;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.codeindex.ICodeIndexFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_NO_CHANGE;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_GATE_BUILD_FAILED;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_MODIFY_FILE_UNREADABLE;
import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_PATH_REJECTED;

/**
 * 修改型落盘（A4-2 / S111）：白名单 → 重放 edit（再次过存在性闸门）→ 写盘 → 编译门禁 → 失败回滚。
 *
 * <p>三个必须：
 * <ol>
 *   <li><b>复用生成型同一道路径白名单</b>（{@link CodingPathWhitelist}）——修改点由 LLM 间接
 *       决定，不加闸门就成任意文件写入口；</li>
 *   <li><b>落盘前重放 edit</b>：不直接写评审时算好的改后文本，而在**当前磁盘内容**上重跑一遍
 *       search 存在性校验，防止评审期间文件已被人改过（search 段失效即拒绝，不静默写坏文件）；</li>
 *   <li><b>门禁失败必须回滚</b>：备份原文后写盘，编译/typecheck 不过则原样还原并抛 7022，
 *       绝不留编译不过的代码在工程里。回滚用内存备份而非 git，不依赖工作区干净。</li>
 * </ol>
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
@Component
public class ModifyApplyService {

    private static final Logger log = LoggerFactory.getLogger(ModifyApplyService.class);

    private final ModifyProperties properties;
    private final LocateProperties locateProperties;
    private final ModifyGate gate;
    private final ICodeIndexFacade indexFacade;
    private final ObjectMapper objectMapper;

    public ModifyApplyService(ModifyProperties properties,
                              LocateProperties locateProperties,
                              ModifyGate gate,
                              ICodeIndexFacade indexFacade,
                              ObjectMapper objectMapper) {
        this.properties = properties;
        this.locateProperties = locateProperties;
        this.gate = gate;
        this.indexFacade = indexFacade;
        this.objectMapper = objectMapper;
    }

    /**
     * 应用修改型会话：写盘 + 门禁，失败回滚并抛 7022。
     *
     * @param session 会话（taskType=5，status 应为 1，由调用方守卫）
     */
    public void apply(CodingSession session) {
        if (!properties.isEnabled()) {
            throw new ServiceException(com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_MODIFY_DISABLED);
        }
        LocateResultVO locate = readLocate(session);
        String relativePath = locate.getChosen() == null ? null : locate.getChosen().getPath();
        if (relativePath == null || relativePath.isBlank()) {
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }
        String repo = locate.getRepo();
        LocateProperties.RepoConfig repoConfig = locateProperties.repo(repo);
        if (repoConfig == null || repoConfig.getRoot() == null || repoConfig.getRoot().isBlank()) {
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }
        Path repoRoot = Path.of(repoConfig.getRoot());
        // 与 prepare 同一道闸门、同一套基准（仓内路径 + 仓库根），避免 prepare 过、apply 拒
        if (!CodingPathWhitelist.allowsInRepo(repoRoot, relativePath)) {
            log.warn("[AI Coding] Modify path rejected: repo={}, path={}", repo, relativePath);
            throw new ServiceException(CODING_PATH_REJECTED);
        }

        EditInstruction instruction = EditInstructionParser.parse(
                session.getEditJson(), relativePath, objectMapper);

        // 落盘前重放：以当前磁盘内容为准再过一次存在性闸门
        String current = readFile(repo, relativePath);
        String modified;
        try {
            modified = instruction.apply(current);
        } catch (ServiceException e) {
            log.warn("[AI Coding] Modify replay rejected: session={}, path={}", session.getId(), relativePath);
            throw e;
        }
        if (current.equals(modified)) {
            // 与 prepare 同一口径：无实际改动不是「文件不可读」，是 edit 无效（7024）
            throw new ServiceException(CODING_EDIT_NO_CHANGE);
        }

        Path target = repoRoot.resolve(relativePath).normalize();
        if (!target.startsWith(repoRoot)) {
            throw new ServiceException(CODING_PATH_REJECTED);
        }
        try {
            Files.writeString(target, modified, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("[AI Coding] Modify write failed: path={}", relativePath, e);
            throw new ServiceException(CODING_PATH_REJECTED);
        }

        // 落盘后门禁：不过则还原
        ModifyGate.BuildCheck build = gate.build(repoRoot, relativePath);
        if (!build.ok()) {
            try {
                Files.writeString(target, current, StandardCharsets.UTF_8);
                log.warn("[AI Coding] Modify rolled back by build gate: session={}, path={}, msg={}",
                        session.getId(), relativePath, build.message());
            } catch (IOException e) {
                log.error("[AI Coding] Rollback failed, file left modified: {}", relativePath, e);
            }
            throw new ServiceException(CODING_GATE_BUILD_FAILED);
        }
        log.info("[AI Coding] Modify applied: session={}, path={}, buildSkipped={}",
                session.getId(), relativePath, build.skipped());
    }

    private LocateResultVO readLocate(CodingSession session) {
        if (session.getLocateJson() == null || session.getLocateJson().isBlank()) {
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }
        try {
            return objectMapper.readValue(session.getLocateJson(), LocateResultVO.class);
        } catch (Exception e) {
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }
    }

    private String readFile(String repo, String relativePath) {
        Optional<String> content = indexFacade.readFile(repo, relativePath);
        if (content.isEmpty()) {
            throw new ServiceException(CODING_MODIFY_FILE_UNREADABLE);
        }
        return content.get();
    }
}
