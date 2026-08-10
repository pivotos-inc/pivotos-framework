package com.pivotos.starter.excel.translator;

/**
 * 字典翻译器接口：由业务模块注入实现，负责字典值↔标签的双向翻译。
 * <p>
 * 导出时调用 {@link #toLabel(String, String)} 将字典值转为可读标签；
 * 导入时调用 {@link #toValue(String, String)} 将 Excel 中的标签反查为字典值。
 * </p>
 *
 * @author PivotOS Team
 * @since 2.1.0
 */
@FunctionalInterface
public interface DictTranslator {

    /**
     * 字典 value → label（导出翻译）
     *
     * @param dictType 字典类型
     * @param value    字典值
     * @return 对应标签，查不到返回原值
     */
    String toLabel(String dictType, String value);

    /**
     * 字典 label → value（导入反查）。默认实现返回原 label，业务方可覆盖。
     *
     * @param dictType 字典类型
     * @param label    Excel 列中的标签文本
     * @return 对应字典值，查不到返回原标签
     */
    default String toValue(String dictType, String label) {
        return label;
    }

    /**
     * 获取指定字典类型的所有 label 列表，用于生成 Excel 模板下拉选项。
     * 默认返回空数组，需要下拉功能时由实现类覆盖。
     *
     * @param dictType 字典类型
     * @return 所有字典标签数组
     * @since 2.1.0
     */
    default String[] allLabels(String dictType) {
        return new String[0];
    }
}
