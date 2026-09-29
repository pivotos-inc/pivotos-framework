package com.pivotos.migration.engine.index;

import com.pivotos.migration.api.codeindex.CodeLayer;
import com.pivotos.migration.api.codeindex.CodeSymbol;
import com.pivotos.migration.api.codeindex.FileSymbolTable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java 源码索引解析器：包名 / 类型 / 注解 / imports / 方法签名 → 符号表。
 *
 * <p>沿用迁移引擎 IR 解析器的正则轻量抽取路线（不引 AST 依赖，JDK 25 直接跑）；
 * 方法抽取同时产出行号，供精定位阶段「行区间」判定与喂入档裁剪。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Order(10)
@Component
public class JavaIndexParser implements SourceIndexParser {

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("^\\s*package\\s+([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final Pattern IMPORT_PATTERN = Pattern.compile("^\\s*import\\s+(?:static\\s+)?([\\w.]+)\\s*;", Pattern.MULTILINE);

    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*"                 // 类级注解
                    + "(?:public\\s+|final\\s+|abstract\\s+)*"
                    + "\\b(class|interface|enum|record)\\s+(\\w+)",
            Pattern.MULTILINE);

    /**
     * 方法声明：访问修饰符 + 可选修饰符 + 返回类型 + 方法名(参数)。
     * 修饰符单独成组：行号须从修饰符处起算——正则前导 {@code ^\s*} 会吃掉上一行的空行，
     * 直接按 match 起点算行号会整体偏上一行（实测偏差 1 行）。
     */
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*"                 // 方法级注解（含跨行注解的单行形态）
                    + "((?:public|protected|private)\\s+)"
                    + "(?:static\\s+|final\\s+|abstract\\s+|synchronized\\s+|default\\s+)*"
                    + "([<\\w.,?\\[\\]\\s]+?)\\s+"
                    + "(\\w+)\\s*\\(([^)]*)\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{",
            Pattern.MULTILINE);

    /** 字段声明（public/private 字段，供状态/常量类改动定位）；同法以修饰符组定行号 */
    private static final Pattern FIELD_PATTERN = Pattern.compile(
            "^\\s*((?:public|protected|private)\\s+)(?:static\\s+|final\\s+)*"
                    + "([\\w<>,.?\\[\\]\\s]+?)\\s+(\\w+)\\s*(?:=|;)",
            Pattern.MULTILINE);

    private static final int MAX_IMPORTS = 60;

    @Override
    public boolean supports(String relativePath) {
        return relativePath != null && relativePath.endsWith(".java");
    }

    @Override
    public FileSymbolTable parse(String relativePath, String content) {
        if (content == null || content.isBlank()) {
            return SourceIndexParser.empty(relativePath, CodeLayer.UNKNOWN, "", 0);
        }
        String pkg = firstGroup(PACKAGE_PATTERN, content, "");
        TypeInfo type = extractType(content);
        CodeLayer layer = LayerClassifier.classify(relativePath, content, type.name(), "interface".equals(type.kind()));

        List<CodeSymbol> symbols = new ArrayList<>();
        collectMethods(content, symbols);
        collectFields(content, symbols);
        symbols.sort(Comparator.comparingInt(CodeSymbol::line));

        int lineCount = countLines(content);
        return new FileSymbolTable(relativePath, layer, type.name(), SummaryExtractor.extract(content), pkg,
                extractImports(content), List.copyOf(symbols), lineCount);
    }

    private void collectMethods(String content, List<CodeSymbol> out) {
        Matcher m = METHOD_PATTERN.matcher(content);
        while (m.find()) {
            String returnType = m.group(2).trim();
            String name = m.group(3);
            String params = m.group(4).trim();
            // 构造器：返回类型为空且方法名与类名同形——由调用方按 typeName 过滤，此处仅排除空名
            if (name.isBlank()) {
                continue;
            }
            int line = lineOf(content, m.start(1));
            out.add(new CodeSymbol(name, "method",
                    returnType + " " + name + "(" + params + ")", line));
        }
    }

    private void collectFields(String content, List<CodeSymbol> out) {
        Matcher m = FIELD_PATTERN.matcher(content);
        while (m.find()) {
            String type = m.group(2).trim();
            String name = m.group(3);
            // 与方法抽取重叠的片段（形如 `public void foo(` 不匹配字段正则的 `=`/`;` 收尾），此处天然不命中
            if (name.isBlank()) {
                continue;
            }
            out.add(new CodeSymbol(name, "field", type + " " + name, lineOf(content, m.start(1))));
        }
    }

    private List<String> extractImports(String content) {
        List<String> imports = new ArrayList<>();
        Matcher m = IMPORT_PATTERN.matcher(content);
        while (m.find() && imports.size() < MAX_IMPORTS) {
            imports.add(m.group(1));
        }
        return List.copyOf(imports);
    }

    private TypeInfo extractType(String content) {
        Matcher m = TYPE_PATTERN.matcher(content);
        if (m.find()) {
            return new TypeInfo(m.group(1).toLowerCase(Locale.ROOT), m.group(2));
        }
        return new TypeInfo("class", "Unknown");
    }

    /** 计算匹配起始偏移所在行号（1 基） */
    static int lineOf(String content, int offset) {
        int line = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    static int countLines(String content) {
        if (content.isEmpty()) {
            return 0;
        }
        int n = 1;
        for (int i = 0; i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    static String firstGroup(Pattern pattern, String content, String fallback) {
        Matcher m = pattern.matcher(content);
        return m.find() ? m.group(1) : fallback;
    }

    private record TypeInfo(String kind, String name) {
    }
}
