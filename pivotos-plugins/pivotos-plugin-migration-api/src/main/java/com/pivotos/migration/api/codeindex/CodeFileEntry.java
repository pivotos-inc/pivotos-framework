package com.pivotos.migration.api.codeindex;

import java.util.List;

/**
 * 模块索引条目（一个源文件一行，供粗定位阶段喂 LLM）。
 *
 * @param relativePath 仓库根相对路径（粗定位要求 LLM 逐字回抄该路径）
 * @param moduleName   所属模块名（Maven 模块 / 前端包名，用于缩小范围）
 * @param layer        分层标签
 * @param typeName     主类型名（Java 类名 / Vue 组件名 / ts 文件名）
 * @param summary      首行有效注释摘要（截断）
 * @param symbols      关键符号名（方法/函数，截断至配置上限）
 * @param lineCount    文件行数（精定位喂入档选择依据）
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public record CodeFileEntry(String relativePath,
                            String moduleName,
                            CodeLayer layer,
                            String typeName,
                            String summary,
                            List<String> symbols,
                            int lineCount) {

    public CodeFileEntry {
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }
}
