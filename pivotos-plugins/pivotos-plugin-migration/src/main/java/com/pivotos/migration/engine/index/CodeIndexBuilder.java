package com.pivotos.migration.engine.index;

import com.pivotos.migration.api.codeindex.CodeFileEntry;
import com.pivotos.migration.api.codeindex.CodeIndexSnapshot;
import com.pivotos.migration.api.codeindex.FileSymbolTable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 代码索引构建器：按 include 通配扫描仓库根，产出「模块索引 + 符号表」快照。
 *
 * <p>构建成本一次性（S107 spike：584 java + 115 ts/vue 全量正则抽取秒级），快照常驻内存，
 * 由门面 rebuildIndex 显式刷新（后续可接 git diff 增量）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Component
public class CodeIndexBuilder {

    private static final Logger log = LoggerFactory.getLogger(CodeIndexBuilder.class);

    /** 索引可见符号数上限（控制粗定位索引体积） */
    private static final int MAX_INDEX_SYMBOLS = 8;

    private static final int MAX_SUMMARY = 60;

    /** 遍历时整体跳过的目录（构建产物 / 依赖 / VCS / 数据目录） */
    private static final Set<String> EXCLUDED_DIRS = Set.of(
            "target", "build", "dist", "node_modules", ".git", ".idea", ".vscode",
            "logs", "coverage", ".codebuddy", ".codeartsdoer", ".qoder", ".arts");

    private final List<SourceIndexParser> parsers;

    public CodeIndexBuilder(List<SourceIndexParser> parsers) {
        this.parsers = parsers == null ? List.of() : List.copyOf(parsers);
    }

    /**
     * 构建索引快照。
     *
     * @param repo     仓库逻辑名
     * @param rootPath 仓库根绝对路径
     * @param includes 相对根的路径通配（为空则扫描全部文本文件）
     * @return 快照；根不存在时返回空快照
     */
    public CodeIndexSnapshot build(String repo, String rootPath, List<String> includes) {
        Path root = Path.of(rootPath == null ? "" : rootPath);
        if (rootPath == null || rootPath.isBlank() || !Files.isDirectory(root)) {
            log.warn("[CodeIndex] 索引根不存在或不可读：repo={} root={}", repo, rootPath);
            return new CodeIndexSnapshot(repo, String.valueOf(rootPath), List.of(), Map.of());
        }
        List<Pattern> patterns = compileIncludes(includes);
        List<Path> files = collectFiles(root, patterns);

        List<CodeFileEntry> entries = new ArrayList<>(files.size());
        Map<String, FileSymbolTable> tables = new LinkedHashMap<>(files.size());
        for (Path file : files) {
            String relative = relativize(root, file);
            String content = readQuietly(file);
            if (content == null) {
                continue;
            }
            FileSymbolTable table = parse(relative, content);
            tables.put(relative, table);
            entries.add(toEntry(relative, table));
        }
        log.info("[CodeIndex] 索引构建完成：repo={} root={} files={}", repo, rootPath, entries.size());
        return new CodeIndexSnapshot(repo, root.toAbsolutePath().toString(),
                List.copyOf(entries), Map.copyOf(tables));
    }

    private CodeFileEntry toEntry(String relative, FileSymbolTable table) {
        List<String> symbols = table.symbols().stream()
                .filter(s -> !"schema".equals(s.kind()))
                .map(com.pivotos.migration.api.codeindex.CodeSymbol::name)
                .distinct()
                .limit(MAX_INDEX_SYMBOLS)
                .toList();
        return new CodeFileEntry(relative, moduleOf(relative), table.layer(), table.typeName(),
                table.summary(), symbols, table.lineCount());
    }

    private FileSymbolTable parse(String relative, String content) {
        for (SourceIndexParser parser : parsers) {
            if (parser.supports(relative)) {
                return parser.parse(relative, content);
            }
        }
        return SourceIndexParser.empty(relative, com.pivotos.migration.api.codeindex.CodeLayer.UNKNOWN,
                "", JavaIndexParser.countLines(content));
    }

    /** 模块名：取 {@code /src/} 之前的路径前缀（Maven 模块 / 前端 app 目录） */
    static String moduleOf(String relative) {
        int idx = relative.indexOf("/src/");
        return idx > 0 ? relative.substring(0, idx) : "";
    }

    static List<Pattern> compileIncludes(List<String> includes) {
        if (includes == null || includes.isEmpty()) {
            return List.of();
        }
        List<Pattern> patterns = new ArrayList<>(includes.size());
        for (String glob : includes) {
            patterns.add(Pattern.compile(globToRegex(glob)));
        }
        return List.copyOf(patterns);
    }

    /**
     * glob → 正则：双星加斜杠表示跨目录（可为空）、双星表示任意、单星表示单段、问号表示单字符。
     *
     * <p>必须单遍扫描转换：用顺序 replace 会二次替换自己吐出的正则元字符——跨目录段替换结果里
     * 带的星号与问号会被后续规则再吃一遍，实测产出 {@code ([^/]:.[^/]x)[^/]} 这类废正则（x 为原斜杠）。
     */
    static String globToRegex(String glob) {
        String source = glob.replace('\\', '/');
        StringBuilder sb = new StringBuilder(source.length() + 8);
        int len = source.length();
        for (int i = 0; i < len; i++) {
            char c = source.charAt(i);
            if (c == '*') {
                boolean doubleStar = i + 1 < len && source.charAt(i + 1) == '*';
                if (doubleStar && i + 2 < len && source.charAt(i + 2) == '/') {
                    sb.append("(?:.*/)?");
                    i += 2;
                } else if (doubleStar) {
                    sb.append(".*");
                    i += 1;
                } else {
                    sb.append("[^/]*");
                }
            } else if (c == '?') {
                sb.append("[^/]");
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private List<Path> collectFiles(Path root, List<Pattern> patterns) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> !isExcluded(root, p))
                    .filter(p -> matches(patterns, relativize(root, p)))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("[CodeIndex] 目录遍历失败：{}", root, e);
            return List.of();
        }
    }

    private boolean matches(List<Pattern> patterns, String relative) {
        if (patterns.isEmpty()) {
            return true;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(relative).matches()) {
                return true;
            }
        }
        return false;
    }

    private boolean isExcluded(Path root, Path path) {
        String relative = relativize(root, path);
        for (String segment : relative.split("/")) {
            if (EXCLUDED_DIRS.contains(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String relativize(Path root, Path path) {
        String relative = root.relativize(path).toString().replace('\\', '/');
        return relative;
    }

    /** 读文件失败返回 null（跳过该文件，不中断整体索引） */
    static String readQuietly(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | OutOfMemoryError e) {
            return null;
        }
    }
}
