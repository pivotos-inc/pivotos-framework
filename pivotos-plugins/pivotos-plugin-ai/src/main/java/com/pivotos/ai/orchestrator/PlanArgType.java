package com.pivotos.ai.orchestrator;

/**
 * 入参类型匹配（A5-1 / S116）——对齐 Spring AI JSON Schema 的类型词表。
 *
 * <p>只在计划期做「静态可得」的那部分判断（含引用的值跳过，交由执行期渲染后再由
 * 工具自身的 JSON Schema 兜底）。宽松放行优于误杀：这里的职责是拦下明显的类型错配，
 * 而不是取代 Schema。
 */
public final class PlanArgType {

    private PlanArgType() {
    }

    /**
     * @param value JSON 侧取值对象
     * @param type  JSON Schema 类型词（string / integer / number / boolean）
     */
    public static boolean matches(Object value, String type) {
        if (type == null) {
            return true;
        }
        return switch (type) {
            case "integer" -> value instanceof Integer || value instanceof Long;
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "string" -> value instanceof String;
            default -> true;
        };
    }
}
