package com.pivotos.migration.engine.index;

import com.pivotos.migration.api.codeindex.CodeIndexSnapshot;
import com.pivotos.migration.api.codeindex.FileSymbolTable;
import com.pivotos.migration.api.codeindex.ICodeIndexFacade;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 代码索引门面实现：快照缓存 + 单文件读取。
 *
 * <p>缓存粒度为仓库名；{@code ensureIndex} 命中即返回（定位链路每次调用都过这一层，
 * 全量重扫会拖垮响应），{@code rebuildIndex} 强制重扫（源码变更后刷新）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Component
public class CodeIndexFacadeImpl implements ICodeIndexFacade {

    private final CodeIndexBuilder builder;

    /** repo → 快照 */
    private final Map<String, CodeIndexSnapshot> snapshots = new ConcurrentHashMap<>();

    /** repo → 索引根绝对路径（供 readFile 定位） */
    private final Map<String, String> roots = new ConcurrentHashMap<>();

    public CodeIndexFacadeImpl(CodeIndexBuilder builder) {
        this.builder = builder;
    }

    @Override
    public Optional<CodeIndexSnapshot> ensureIndex(String repo, String rootPath, List<String> includes) {
        if (repo == null || repo.isBlank()) {
            return Optional.empty();
        }
        CodeIndexSnapshot cached = snapshots.get(repo);
        if (cached != null) {
            return Optional.of(cached);
        }
        return rebuildIndex(repo, rootPath, includes);
    }

    @Override
    public Optional<CodeIndexSnapshot> rebuildIndex(String repo, String rootPath, List<String> includes) {
        if (repo == null || repo.isBlank() || rootPath == null || rootPath.isBlank()) {
            return Optional.empty();
        }
        CodeIndexSnapshot snapshot = builder.build(repo, rootPath, includes);
        if (snapshot.entries().isEmpty()) {
            // 空索引不覆盖既有快照，避免误配 root 时把可用索引冲掉
            return snapshots.containsKey(repo) ? Optional.of(snapshots.get(repo)) : Optional.of(snapshot);
        }
        snapshots.put(repo, snapshot);
        roots.put(repo, snapshot.rootPath());
        return Optional.of(snapshot);
    }

    @Override
    public Optional<FileSymbolTable> symbolTable(String repo, String relativePath) {
        if (repo == null || relativePath == null) {
            return Optional.empty();
        }
        CodeIndexSnapshot snapshot = snapshots.get(repo);
        if (snapshot == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(snapshot.symbolTables().get(normalize(relativePath)));
    }

    @Override
    public Optional<String> readFile(String repo, String relativePath) {
        String root = roots.get(repo);
        if (root == null || relativePath == null) {
            return Optional.empty();
        }
        Path file = Path.of(root).resolve(normalize(relativePath)).normalize();
        // 路径穿越防护：解析后必须仍在索引根之内
        if (!file.startsWith(Path.of(root).normalize())) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.ofNullable(CodeIndexBuilder.readQuietly(file));
    }

    @Override
    public Map<String, Integer> stats() {
        Map<String, Integer> result = new LinkedHashMap<>();
        snapshots.forEach((repo, snapshot) -> result.put(repo, snapshot.size()));
        return result;
    }

    @Override
    public void invalidate(String repo) {
        if (repo == null) {
            return;
        }
        snapshots.remove(repo);
        roots.remove(repo);
    }

    /** 统一相对路径写法（去掉前导 ./ 与反斜杠） */
    private static String normalize(String relativePath) {
        String path = relativePath.replace('\\', '/');
        while (path.startsWith("./")) {
            path = path.substring(2);
        }
        return path;
    }
}
