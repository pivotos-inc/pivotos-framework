package com.pivotos.ai.kb.api.enums;

/**
 * 知识库类型（A4E / S117）：把「制度库」从命名约定升级为契约字段。
 *
 * <p>为什么必须是枚举常量而不是自由文本：审批建议的自动预审要求
 * 「本次建议是否有制度依据」是<b>确定性判定</b>；若用自由文本（哪怕是
 * 「制度库」三个字），判定就退化成字符串包含匹配，随命名习惯漂移。
 * 取值与 {@code ai_kb_base.kb_type} 列严格对应。
 */
public final class KbType {

    private KbType() {
    }

    /** 制度类（可支撑审批依据） */
    public static final String POLICY = "policy";

    /** 通用类（不可作为自动预审的制度依据） */
    public static final String GENERAL = "general";

    public static boolean isPolicy(String value) {
        return POLICY.equals(value);
    }
}
