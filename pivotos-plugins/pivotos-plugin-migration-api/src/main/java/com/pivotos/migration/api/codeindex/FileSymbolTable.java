package com.pivotos.migration.api.codeindex;

import java.util.List;

/**
 * 单文件符号表（精定位阶段喂入，抑制文件外符号幻觉）。
 *
 * <p>S107 K3 实证：所有语义失败案都臆造了文件外符号（isTemplateEnabled / BusinessException /
 * pageByUserWithUnreadFirst / onError 三参签名）。15 号文档对策是喂「目标文件 imports + 关联类
 * 签名」，故本表同时携带 imports（该文件可见的外部符号）与 symbols（本文件声明的符号）。
 *
 * @param relativePath 仓库根相对路径
 * @param layer        分层标签
 * @param typeName     主类型名
 * @param summary      文件摘要（首个有效注释，截断）
 * @param packageName  Java 包名（前端文件为空串）
 * @param imports      该文件 import 的外部符号（前端为 import 源，截断至上限）
 * @param symbols      本文件声明的符号条目（按行号升序）
 * @param lineCount    文件总行数
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public record FileSymbolTable(String relativePath,
                              CodeLayer layer,
                              String typeName,
                              String summary,
                              String packageName,
                              List<String> imports,
                              List<CodeSymbol> symbols,
                              int lineCount) {

    public FileSymbolTable {
        imports = imports == null ? List.of() : List.copyOf(imports);
        symbols = symbols == null ? List.of() : List.copyOf(symbols);
    }

    /** 符号名集合（供关键词命中判定） */
    public List<String> symbolNames() {
        return symbols.stream().map(CodeSymbol::name).toList();
    }
}
