package com.pivotos.migration.engine.index;

import com.pivotos.migration.api.codeindex.CodeLayer;
import com.pivotos.migration.api.codeindex.CodeSymbol;
import com.pivotos.migration.api.codeindex.FileSymbolTable;

import java.util.List;

/**
 * 源码索引解析器：单文件 → 符号表（IR 解析器扩展）。
 *
 * <p>与迁移引擎既有 {@code JavaSourceCodeParser / VueSourceCodeParser} 同款思路——正则轻量
 * 抽取，不引第三方 AST 依赖；差别在于本解析器产出「模块索引 + 符号表」而非迁移 IR 节点，
 * 并额外抽取 imports 以抑制文件外符号幻觉（S107 K3 / 15 号文档对策）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public interface SourceIndexParser {

    /** 是否支持该文件（按扩展名/路径） */
    boolean supports(String relativePath);

    /**
     * 解析单文件。
     *
     * @param relativePath 仓库根相对路径
     * @param content      文件全文
     * @return 符号表
     */
    FileSymbolTable parse(String relativePath, String content);

    /** 空符号表构造（解析失败/空文件的兜底） */
    static FileSymbolTable empty(String relativePath, CodeLayer layer, String typeName, int lineCount) {
        return new FileSymbolTable(relativePath, layer, typeName, "", "",
                List.<String>of(), List.<CodeSymbol>of(), lineCount);
    }
}
