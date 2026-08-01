package com.pivotos.starter.excel.util;

import com.pivotos.starter.excel.util.ExcelImportResult.ImportError;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * Excel 导入结果：包含成功行列表和错误行回执。
 *
 * @param <T> 数据类型
 * @author PivotOS Team
 * @since 2.1.0
 */
@Getter
@AllArgsConstructor
public class ExcelImportResult<T> {
    /** 导入成功的行 */
    private final List<T> successRows;
    /** 导入失败的行（含行号和错误信息） */
    private final List<ImportError> errors;

    /** 单行导入错误 */
    @Getter
    @AllArgsConstructor
    public static class ImportError {
        /** Excel 行号 */
        private final int rowNum;
        /** 错误描述 */
        private final String message;
    }
}
