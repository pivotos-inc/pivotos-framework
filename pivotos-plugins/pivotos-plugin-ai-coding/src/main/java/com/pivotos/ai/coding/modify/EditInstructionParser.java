package com.pivotos.ai.coding.modify;

import com.pivotos.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_EDIT_PARSE_FAILED;

/**
 * LLM 输出 → 结构化 edit 指令（A4-2 / S111）。
 *
 * <p>容错但不放水：允许 ```json 代码块包裹、允许 edits/blocks 两种键名，
 * 但**不允许**缺失 search（除 append 块）或缺失 path——缺了就是不可校验的指令，直接 7018。
 *
 * @author PivotOS
 * @since 2.14.0（S111 A4-2）
 */
public final class EditInstructionParser {

    private static final Logger log = LoggerFactory.getLogger(EditInstructionParser.class);

    private EditInstructionParser() {
    }

    /**
     * 解析 LLM 输出。
     *
     * @param raw      LLM 原始输出（可含 ```json 包裹）
     * @param fallback 路径缺省时使用的兜底路径（定位结果）
     * @param mapper   Jackson 3 ObjectMapper
     * @return 结构化 edit 指令
     */
    public static EditInstruction parse(String raw, String fallback, ObjectMapper mapper) {
        JsonNode root = extractJson(raw, mapper);
        if (root == null) {
            throw fail("无法从 LLM 输出中抽出 JSON 对象", raw);
        }
        String path = textOrNull(root.get("path"));
        if (path == null || path.isBlank()) {
            path = fallback;
        }
        if (path == null || path.isBlank()) {
            throw fail("缺少 path", raw);
        }
        JsonNode editsNode = root.get("edits");
        if (editsNode == null || !editsNode.isArray()) {
            editsNode = root.get("blocks");
        }
        if (editsNode == null || !editsNode.isArray() || editsNode.isEmpty()) {
            throw fail("edits 缺失或为空数组", raw);
        }
        List<EditInstruction.Block> blocks = new ArrayList<>();
        for (JsonNode node : editsNode) {
            boolean append = node.get("append") != null && node.get("append").asBoolean(false);
            String search = textOrNull(node.get("search"));
            String replace = textOrNull(node.get("replace"));
            int occurrence = node.get("occurrence") == null ? 0 : node.get("occurrence").asInt(0);
            String reason = textOrNull(node.get("reason"));
            if (!append && (search == null || search.isEmpty())) {
                // 既非追加又无 search —— 不可校验，拒绝
                throw fail("块既非 append 又缺 search", raw);
            }
            blocks.add(new EditInstruction.Block(search == null ? "" : search,
                    replace == null ? "" : replace, occurrence, append, reason));
        }
        return new EditInstruction(path, EditInstruction.sanitize(blocks));
    }

    /**
     * 7018 统一出口：记下失败原因与 LLM 原始输出（截断 800 字）。
     *
     * <p>首轮 E2E 的 I4 只留下一行「7018」，无从判断是模型不守 schema 还是解析器过严；
     * 没有这条日志就只能盲目放宽解析器（放水），有了才能定性后再决定。
     */
    private static ServiceException fail(String reason, String raw) {
        log.warn("[AI Coding] Edit parse failed: reason={}, raw={}", reason, excerpt(raw));
        return new ServiceException(CODING_EDIT_PARSE_FAILED);
    }

    /** LLM 原始输出摘录（截断 800 字），供 7018 定性用 */
    public static String excerpt(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        return value.length() > 800 ? value.substring(0, 800) + "…（截断）" : value;
    }

    /** 抽出 JSON：优先 ```json 代码块，其次首个 { 到末个 } */
    static JsonNode extractJson(String raw, ObjectMapper mapper) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        int fenceStart = text.indexOf("```");
        if (fenceStart >= 0) {
            int lineEnd = text.indexOf('\n', fenceStart);
            int fenceEnd = text.indexOf("```", lineEnd < 0 ? fenceStart + 3 : lineEnd);
            if (lineEnd >= 0 && fenceEnd > lineEnd) {
                text = text.substring(lineEnd + 1, fenceEnd).trim();
            }
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString("");
    }
}
