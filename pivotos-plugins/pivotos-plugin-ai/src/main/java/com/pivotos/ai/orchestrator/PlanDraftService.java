package com.pivotos.ai.orchestrator;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 计划草案生成（A5-1 / S116）——把自然语言意图翻译成 {@link ToolPlan}。
 *
 * <p>职责边界（同 A4 路线）：本类<b>只产出结构化草案</b>，产出后必须由
 * {@link ToolPlanValidator} 过闸；本类自身不做任何「可执行」动作，也不替用户确认写操作。
 *
 * <p>提示词契约里最容易翻车的一处（本轮实证踩到，见《S116 开工简报》§七.2）：
 * <b>prompt 中的引用占位符样例必须写成单花括号 {@code ${stepN.path}}</b>。
 * LLM 会把 prompt 里的写法 100% 模仿过去——一旦样例写成双花括号（转义残留），
 * 模型产出的所有引用都会带双花括号，导致引用全部不可解析（一票否决点首轮 4/8 即此因）。
 * 因此本类的模板字符串<b>不使用任何转义或拼接</b>，占位符原样落盘。
 *
 * @author PivotOS
 * @since 2.15.0（S116 A5-1）
 */
@Service
public class PlanDraftService {

    private static final Logger log = LoggerFactory.getLogger(PlanDraftService.class);


    private final PlanDraftLlmClient llmClient;
    private final OrchestratorProperties properties;

    public PlanDraftService(PlanDraftLlmClient llmClient, OrchestratorProperties properties) {
        this.llmClient = llmClient;
        this.properties = properties;
    }

    /**
     * 由意图生成计划草案。
     *
     * @param intent    用户意图
     * @param toolSpecs 活工具目录（确定 LLM 能看到的工具面）
     * @return 计划（未通过校验时为空计划 + errors）
     */
    public PlanDraft draft(String intent, Map<String, ToolSpec> toolSpecs) {
        String system = buildSystemPrompt(toolSpecs);
        String raw = llmClient.call(system, "用户意图：" + intent, properties.getModel());
        try {
            return new PlanDraft(parsePlan(raw), raw);
        } catch (ServiceException e) {
            // 解析失败必须带上原文（S107 K5：索引类产物第一个动作是打印样本逐字段看）
            log.warn("[PivotOS] 编排计划解析失败，原文全文={}", raw);
            throw e;
        }
    }

    /** 计划草案：计划本体 + 模型原文（原文留证，用于复盘规划质量时还原现场） */
    public record PlanDraft(ToolPlan plan, String rawOutput) {
    }

    // ------------------------------------------------------------ prompt 构建

    public String buildSystemPrompt(Map<String, ToolSpec> toolSpecs) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是 PivotOS 平台的工具编排规划器。唯一职责：把用户的自然语言意图翻译成一条结构化的工具调用链（plan）。\n\n");
        sb.append("可用工具（只能使用以下工具，禁止发明清单外的工具）：\n");
        for (ToolSpec spec : toolSpecs.values()) {
            sb.append("- ").append(spec.name()).append("（").append(spec.write() ? "写操作" : "只读").append("）\n");
            sb.append("  能力：").append(spec.description()).append("\n");
            if (!spec.properties().isEmpty()) {
                sb.append("  参数：");
                List<String> parts = new ArrayList<>();
                for (Map.Entry<String, String> entry : spec.properties().entrySet()) {
                    parts.add(entry.getKey() + ":" + entry.getValue()
                            + (spec.required().contains(entry.getKey()) ? "(必填)" : ""));
                }
                sb.append(String.join(", ", parts)).append("\n");
            }
        }
        sb.append("\nplan 契约（严格遵守）：\n");
        sb.append("{\n");
        sb.append("  \"goal\": \"对用户意图的一句话复述\",\n");
        sb.append("  \"steps\": [\n");
        sb.append("    {\"no\": 1, \"tool\": \"工具名\", \"args\": {\"参数名\": \"参数值\"}, \"reason\": \"为什么这一步\"}\n");
        sb.append("  ],\n");
        sb.append("  \"unmapped\": \"若现有工具无法完成该意图，说明缺什么能力；能完成时填空字符串\"\n");
        sb.append("}\n\n");
        sb.append("硬规则（违反即视为无效 plan）：\n");
        sb.append("1. 参数必须与工具签名一致，未知参数名不要写。\n");
        sb.append("2. 需要把上一步结果传给下一步时，使用引用占位符 ${stepN.路径}，N 必须是已存在的前序步骤序号，");
        sb.append("路径按工具返回结构书写，例如 ${step1.list[0].instanceId}、${step1.list[0].title}。");
        sb.append("标量工具直接使用 ${step1}。引用可以与其他文字拼接在同一个字符串参数里。\n");
        sb.append("3. 写操作工具的 confirm 参数必须为 false，由平台在用户确认后接管，你不得代为确认。\n");
        sb.append("4. 只读工具把 confirm 写 false 即可。\n");
        sb.append("5. 现有工具无法完成意图时：steps 传空数组，并在 unmapped 说明缺什么；不要编造工具，不要用无关工具拼凑。\n");
        sb.append("6. 最多 ").append(properties.getMaxSteps()).append(" 步；只输出 JSON，不要输出 Markdown 代码块围栏，不要输出解释性文字。\n");
        return sb.toString();
    }

    // ------------------------------------------------------------ 解析

    /**
     * 从模型原文抽取计划：去 Markdown 围栏 → 截取顶层 JSON 对象 → 映射成 record。
     */
    public ToolPlan parsePlan(String raw) {
        JSONObject root = extractJsonObject(raw);
        if (root == null) {
            throw new ServiceException(AiErrorCode.ORCHESTRATOR_PLAN_EMPTY, "规划器未产出可解析的调用链");
        }
        String goal = root.getString("goal") == null ? "" : root.getString("goal");
        String unmapped = root.getString("unmapped") == null ? "" : root.getString("unmapped");
        List<PlanStep> steps = new ArrayList<>();
        JSONArray array = root.getJSONArray("steps");
        if (array != null) {
            for (int i = 0; i < array.size(); i++) {
                JSONObject item = array.getJSONObject(i);
                if (item == null) {
                    continue;
                }
                int no = item.getIntValue("no") == 0 ? i + 1 : item.getIntValue("no");
                Map<String, Object> args = new LinkedHashMap<>();
                JSONObject argsJson = item.getJSONObject("args");
                if (argsJson != null) {
                    for (String key : argsJson.keySet()) {
                        args.put(key, argsJson.get(key));
                    }
                }
                steps.add(new PlanStep(no, item.getString("tool"), args, item.getString("reason")));
            }
        }
        return new ToolPlan(goal, steps, unmapped);
    }

    /** 去围栏 + 截取第一个顶层 JSON 对象 */
    private JSONObject extractJsonObject(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        if (text.startsWith("```")) {
            text = text.replaceFirst("^```[a-zA-Z]*", "");
            int lastFence = text.lastIndexOf("```");
            if (lastFence > 0) {
                text = text.substring(0, lastFence);
            }
            text = text.trim();
        }
        try {
            return JSON.parseObject(text);
        } catch (Exception ignored) {
            // 落到这里说明原文带前后缀文字：截取第一个完整 JSON 对象
        }
        int start = text.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    try {
                        return JSON.parseObject(text.substring(start, i + 1));
                    } catch (Exception e) {
                        return null;
                    }
                }
            }
        }
        return null;
    }
}
