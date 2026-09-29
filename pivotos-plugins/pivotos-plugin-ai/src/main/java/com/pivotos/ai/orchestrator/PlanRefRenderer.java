package com.pivotos.ai.orchestrator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONException;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.common.core.exception.ServiceException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划引用渲染器（A5-1 / S116）——多步编排里「步骤间数据传递」的唯一实现。
 *
 * <p>语法（与喂给 LLM 的 prompt 契约严格一致）：
 * <pre>
 *   ${stepN}                    整串替换第 N 步返回（标量工具 / 纯文本工具走这条）
 *   ${stepN.field}              JSON 返回按字段取
 *   ${stepN.list[0].field}      JSON 返回按「下标 + 字段」逐层取
 *   "${step1} 项待办（${step2.list[0].title}）"   同一参数内可多处引用，整体落成字符串
 * </pre>
 *
 * <p>三条设计取舍：
 * <ol>
 *   <li><b>严格失败</b>：路径取不到就抛 {@link AiErrorCode#ORCHESTRATOR_REF_INVALID}，
 *       不做「沉默填空字符串」——半截参数的写操作比失败更危险（E2E 会实证到此形态）。</li>
 *   <li><b>保持原生类型</b>：整个参数恰好是一个引用时，返回 JSON 里的<b>原始类型</b>
 *       （Number/Boolean/String），而不是它的字符串形式。Spring AI 的
 *       {@code MethodToolCallback} 按 JSON Schema 严格校验入参类型（S98 K1 实证），
 *       把 {@code instanceId} 传成 {@code "123"} 会被 Schema 拒掉。</li>
 *   <li><b>非 JSON 返回整体透传</b>：工具返回是杂合文本（不是合法 JSON）时，
 *       只有 {@code ${stepN}} 这种整串引用可用，带路径的引用按路径不可达处理——
 *       不做猜测式二次解析，显式失败优于猜错。</li>
 * </ol>
 *
 * @author PivotOS
 * @since 2.15.0（S116 A5-1）
 */
public final class PlanRefRenderer {

    private PlanRefRenderer() {
    }

    /** 引用表达式：group(1)=步骤序号，group(2)=路径（可为空） */
    private static final Pattern REF = Pattern.compile("\\$\\{step(\\d+)((?:\\.[A-Za-z0-9_]+|\\[\\d+\\])*)\\}");

    /**
     * 渲染一个入参值。
     *
     * @param value   计划里书写的原始值（可能是数字/布尔/含引用的字符串）
     * @param outputs 已执行步骤的输出（key = 步骤序号）
     * @return 可直接塞进工具入参 JSON 的值（原生类型或字符串）
     */
    public static Object render(Object value, Map<Integer, String> outputs) {
        if (!(value instanceof String text)) {
            return value;
        }
        if (!text.contains("${")) {
            return text;
        }
        Matcher matcher = REF.matcher(text);
        // 整串就是一个引用 → 保持上游返回值的原生类型
        if (isWholeRef(text)) {
            matcher.reset();
            if (matcher.find()) {
                return resolve(outputs, Integer.parseInt(matcher.group(1)), matcher.group(2));
            }
        }
        // 含拼接 → 落成字符串
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            Object resolved = resolve(outputs, Integer.parseInt(matcher.group(1)), matcher.group(2));
            matcher.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf(resolved)));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /** 该引用指向的步骤序号（-1 表示不含引用） */
    public static List<Integer> refTargets(String value) {
        List<Integer> targets = new ArrayList<>();
        if (value == null) {
            return targets;
        }
        Matcher matcher = REF.matcher(value);
        while (matcher.find()) {
            targets.add(Integer.parseInt(matcher.group(1)));
        }
        return targets;
    }

    /** 是否含引用表达式 */
    public static boolean hasRef(Object value) {
        return value instanceof String text && REF.matcher(text).find();
    }

    private static boolean isWholeRef(String text) {
        Matcher matcher = REF.matcher(text);
        return matcher.matches() || (matcher.find() && matcher.start() == 0 && matcher.end() == text.length());
    }

    private static Object resolve(Map<Integer, String> outputs, int stepNo, String path) {
        String raw = outputs.get(stepNo);
        if (raw == null) {
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_REF_INVALID,
                    "第 " + stepNo + " 步尚未执行或没有返回，无法引用");
        }
        if (path == null || path.isEmpty()) {
            return coerceScalar(raw);
        }
        Object node = parseJson(raw);
        Object cursor = node;
        // 路径形如 .list[0].instanceId —— 单遍扫描，一次吞一个「.字段」或「[下标」
        int i = 0;
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                int start = i + 1;
                int end = start;
                while (end < path.length() && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                String field = path.substring(start, end);
                if (!(cursor instanceof Map<?, ?> map)) {
                    throw unreachable(stepNo, path, i);
                }
                cursor = map.get(field);
                if (cursor == null) {
                    throw unreachable(stepNo, path, field);
                }
                i = end;
            } else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    throw unreachable(stepNo, path, i);
                }
                int index = Integer.parseInt(path.substring(i + 1, close));
                if (!(cursor instanceof List<?> list) || index >= list.size()) {
                    throw unreachable(stepNo, path, i);
                }
                cursor = list.get(index);
                i = close + 1;
            } else {
                throw unreachable(stepNo, path, i);
            }
        }
        return cursor;
    }

    private static ServiceException unreachable(int stepNo, String path, Object at) {
        return new ServiceException(AiErrorCode.ORCHESTRATOR_REF_INVALID,
                "第 " + stepNo + " 步返回中取不到路径 step" + stepNo + path + "（断点：" + at + "）");
    }

    /** 工具返回是不是 JSON：是则结构化取路径，否则原样文本 */
    private static Object parseJson(String raw) {
        String trimmed = raw.trim();
        if (!(trimmed.startsWith("{") || trimmed.startsWith("["))) {
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_REF_INVALID,
                    "被引用步骤的返回不是结构化数据（无法按路径取值）");
        }
        try {
            return JSON.parse(trimmed);
        } catch (JSONException e) {
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_REF_INVALID,
                    "被引用步骤的返回不是合法 JSON（无法按路径取值）");
        }
    }

    /**
     * 整串引用场景：把纯文本返回尽可能还原成原生类型，
     * 让 {@code queryMyPendingTaskCount} 这类标量工具的产出能直接当数字参数使用。
     */
    private static Object coerceScalar(String raw) {
        String trimmed = raw.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return JSON.parse(trimmed);
        }
        if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
            return Boolean.valueOf(trimmed);
        }
        if (trimmed.matches("-?\\d+")) {
            try {
                return Long.valueOf(trimmed);
            } catch (NumberFormatException e) {
                return new BigDecimal(trimmed);
            }
        }
        return raw;
    }
}
