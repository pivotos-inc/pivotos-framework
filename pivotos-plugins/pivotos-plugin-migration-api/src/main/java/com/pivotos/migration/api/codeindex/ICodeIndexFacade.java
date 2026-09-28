package com.pivotos.migration.api.codeindex;

import java.util.List;
import java.util.Optional;

/**
 * 代码索引门面（A4-1 代码索引与定位服务对外的唯一入口）。
 *
 * <p>由迁移引擎的 IR 解析器扩展实现（JavaSourceCodeParser / VueSourceCodeParser 同款正则轻量
 * AST 抽取思路），供 AI Coding 插件消费；插件边界按 A1/A2 口径只经 api 包访问，实现类在
 * {@code com.pivotos.migration.engine.index}。迁移插件未装配时调用方应按 {@link Optional#empty()}
 * 降级（AI Coding 侧以 ObjectProvider 注入实现优雅降级）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public interface ICodeIndexFacade {

    /**
     * 取索引快照（缓存命中直接返回，未命中则构建）。
     *
     * @param repo     仓库逻辑名
     * @param rootPath 仓库根绝对路径
     * @param includes 相对根的路径通配（如 {@code pivotos-plugins/**\/src/main/java/**\/*.java}）
     * @return 索引快照；根路径不存在或不可读时返回空
     */
    Optional<CodeIndexSnapshot> ensureIndex(String repo, String rootPath, List<String> includes);

    /**
     * 强制重建索引（源码变更后刷新）。
     *
     * @param repo     仓库逻辑名
     * @param rootPath 仓库根绝对路径
     * @param includes 相对根的路径通配
     * @return 索引快照；根路径不存在或不可读时返回空
     */
    Optional<CodeIndexSnapshot> rebuildIndex(String repo, String rootPath, List<String> includes);

    /**
     * 取单文件符号表。
     *
     * @param repo         仓库逻辑名
     * @param relativePath 仓库根相对路径
     * @return 符号表；文件不在索引内时返回空
     */
    Optional<FileSymbolTable> symbolTable(String repo, String relativePath);

    /**
     * 取单文件全文（精定位喂入用）。
     *
     * @param repo         仓库逻辑名
     * @param relativePath 仓库根相对路径
     * @return 文件全文；不存在时返回空
     */
    Optional<String> readFile(String repo, String relativePath);

    /**
     * 索引统计（仓库名 → 文件数），供运维端点/日志核对。
     */
    java.util.Map<String, Integer> stats();

    /** 失效指定仓库索引缓存 */
    void invalidate(String repo);
}
