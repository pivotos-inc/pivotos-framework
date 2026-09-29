package com.pivotos.ai.orchestrator;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 编排计划中的一个步骤（A5-1 / S116）。
 *
 * <p><b>必须是 record</b>：本对象走「LLM 产出 → 序列化入库 → 读回重放」链路，
 * Jackson 3 对普通类按 getter 序列化，普通类写成 record 风格访问器（{@code no()} / {@code tool()}）
 * 会得到空对象（S111/S112 的 EditInstruction 断点事故同源），本类从出生那天起就是 record。
 *
 * @param no     步骤序号（从 1 严格递增，引用只能指向更小的序号）
 * @param tool   工具名（须命中活工具注册表）
 * @param args   入参：键为工具 JSON Schema 的参数名，值为 JSON 原生类型或含 {@code ${stepN...}} 引用的字符串
 * @param reason 规划理由（面向人的可解释性，不参与执行）
 */
public record PlanStep(int no, String tool, Map<String, Object> args, String reason) {

    /**
     * 紧凑构造：args 为空落成不可变空 Map，避免下游到处判空。
     *
     * <p>不用 {@code Map.copyOf}：参数值可能含 null（模型常见的「显式留空」写法），
     * copyOf 遇 null 键/值会抛 NPE，而这里需要的是「交给校验器给出可读结论」。
     */
    public PlanStep {
        args = args == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(args));
    }
}
