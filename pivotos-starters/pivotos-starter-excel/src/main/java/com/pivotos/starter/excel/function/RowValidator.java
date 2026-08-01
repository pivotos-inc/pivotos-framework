package com.pivotos.starter.excel.function;

/**
 * 单行校验接口：导入时由业务模块实现，对每一行进行字段级校验。
 * <p>
 * 校验通过时正常返回；校验不通过时抛出异常（异常信息即导入报告中对应行的错误描述）。
 * ExcelHelper 会逐行捕获并记录到 {@link com.pivotos.starter.excel.util.ExcelImportResult.ImportError}。
 * </p>
 *
 * @param <T> 数据类型
 * @author PivotOS Team
 * @since 2.1.0
 */
@FunctionalInterface
public interface RowValidator<T> {

    /**
     * 校验单行数据
     *
     * @param row 行数据（已通过字典翻译）
     * @throws RuntimeException 校验不通过（message 即错误描述）
     */
    void validate(T row);
}
