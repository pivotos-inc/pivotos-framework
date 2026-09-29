package com.pivotos.migration.api.codeindex;

/**
 * 代码符号条目（符号表最小单元）。
 *
 * <p>用途：A4-1 的两处关键消费——①粗定位索引里带上「关键符号名」给 LLM 更多召回线索；
 * ②精定位阶段喂入「目标文件符号表」，抑制文件外符号幻觉（S107 K3）。
 *
 * @param name      符号名（方法名 / 函数名 / 类型名 / 字段名）
 * @param kind      符号种类：method / function / type / field / schema
 * @param signature 签名原文（方法带参数与返回类型；函数带参数；类型为空）
 * @param line      声明所在行号（1 基）
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public record CodeSymbol(String name, String kind, String signature, int line) {

    /** 便于日志/断言的可读形态：name(line) */
    @Override
    public String toString() {
        return name + "(" + line + ")";
    }
}
