package com.pivotos.migration.api.codeindex;

import java.util.List;
import java.util.Map;

/**
 * 仓库代码索引快照（模块索引 + 符号表全集）。
 *
 * @param repo        仓库名（配置中的逻辑名，如 fw / ui）
 * @param rootPath    索引根绝对路径
 * @param entries     模块索引条目（按相对路径升序）
 * @param symbolTables 相对路径 → 符号表（懒加载全量，定位阶段按需取用）
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public record CodeIndexSnapshot(String repo,
                                String rootPath,
                                List<CodeFileEntry> entries,
                                Map<String, FileSymbolTable> symbolTables) {

    public CodeIndexSnapshot {
        entries = entries == null ? List.of() : List.copyOf(entries);
        symbolTables = symbolTables == null ? Map.of() : Map.copyOf(symbolTables);
    }

    /** 索引文件数 */
    public int size() {
        return entries.size();
    }
}
