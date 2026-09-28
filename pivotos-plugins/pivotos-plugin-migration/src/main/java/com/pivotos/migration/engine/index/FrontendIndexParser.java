package com.pivotos.migration.engine.index;

import com.pivotos.migration.api.codeindex.CodeLayer;
import com.pivotos.migration.api.codeindex.CodeSymbol;
import com.pivotos.migration.api.codeindex.FileSymbolTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 前端源码索引解析器（.vue / .ts / .tsx / .js）：导出函数 / import / 表单字段 → 符号表。
 *
 * <p>与迁移引擎 {@code VueSourceCodeParser} 同款正则路线；额外抽取 {@code field: 'xxx'}
 * 形态的表单字段（YForm schema），供「某个字段加校验」类意图定位（spike I7 形态）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Order(20)
@Component
public class FrontendIndexParser implements SourceIndexParser {

    /** 导出函数；export 单独成组用于定行号（前导 ^\s* 会吃掉上一行空行，按 match 起点算会偏上一行） */
    private static final Pattern EXPORT_FUNCTION_PATTERN = Pattern.compile(
            "^\\s*(export)\\s+(?:async\\s+)?function\\s+(\\w+)\\s*\\(([^)]*)\\)", Pattern.MULTILINE);

    private static final Pattern EXPORT_CONST_PATTERN = Pattern.compile(
            "^\\s*(export)\\s+const\\s+(\\w+)", Pattern.MULTILINE);

    private static final Pattern IMPORT_PATTERN = Pattern.compile(
            "^\\s*import\\s+.+?from\\s+['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

    /** YForm / Element 表单 schema 字段名：field: 'postCode' */
    private static final Pattern SCHEMA_FIELD_PATTERN = Pattern.compile(
            "field:\\s*['\"]([\\w.]+)['\"]", Pattern.MULTILINE);

    /** Vue 组件名（defineOptions({ name })）或 PascalCase 文件名 */
    private static final Pattern COMPONENT_NAME_PATTERN = Pattern.compile(
            "defineOptions\\s*\\(\\s*\\{[^}]*name:\\s*['\"]([^'\"]+)['\"]", Pattern.MULTILINE);

    private static final int MAX_IMPORTS = 30;

    @Override
    public boolean supports(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        return relativePath.endsWith(".vue") || relativePath.endsWith(".ts")
                || relativePath.endsWith(".tsx") || relativePath.endsWith(".js");
    }

    @Override
    public FileSymbolTable parse(String relativePath, String content) {
        if (content == null || content.isBlank()) {
            return SourceIndexParser.empty(relativePath, CodeLayer.UNKNOWN, "", 0);
        }
        String typeName = extractTypeName(relativePath, content);
        CodeLayer layer = LayerClassifier.classify(relativePath, content, typeName, false);

        List<CodeSymbol> symbols = new ArrayList<>();
        Matcher fn = EXPORT_FUNCTION_PATTERN.matcher(content);
        while (fn.find()) {
            symbols.add(new CodeSymbol(fn.group(2), "function",
                    fn.group(2) + "(" + fn.group(3).trim() + ")", JavaIndexParser.lineOf(content, fn.start(1))));
        }
        Matcher constant = EXPORT_CONST_PATTERN.matcher(content);
        while (constant.find()) {
            symbols.add(new CodeSymbol(constant.group(2), "const", constant.group(2),
                    JavaIndexParser.lineOf(content, constant.start(1))));
        }
        Matcher field = SCHEMA_FIELD_PATTERN.matcher(content);
        while (field.find()) {
            symbols.add(new CodeSymbol(field.group(1), "schema", "field: " + field.group(1),
                    JavaIndexParser.lineOf(content, field.start())));
        }
        symbols.sort(Comparator.comparingInt(CodeSymbol::line));

        List<String> imports = new ArrayList<>();
        Matcher im = IMPORT_PATTERN.matcher(content);
        while (im.find() && imports.size() < MAX_IMPORTS) {
            imports.add(im.group(1));
        }

        return new FileSymbolTable(relativePath, layer, typeName,
                SummaryExtractor.extract(content), "",
                List.copyOf(imports), List.copyOf(symbols), JavaIndexParser.countLines(content));
    }

    /** 组件名优先取 defineOptions.name，否则回退文件名（去扩展名） */
    private String extractTypeName(String relativePath, String content) {
        Matcher m = COMPONENT_NAME_PATTERN.matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        String fileName = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }
}
