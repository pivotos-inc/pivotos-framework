package com.pivotos.migration.engine.parse;

import com.pivotos.migration.domain.entity.MigrationFile;
import com.pivotos.migration.domain.entity.MigrationTask;

import java.nio.file.Path;

/**
 * 源码解析器 SPI。
 *
 * <p>每个实现负责一种文件类型的解析。
 */
public interface SourceCodeParser {

    /**
     * 是否支持解析该文件。
     *
     * @param task 迁移任务
     * @param file 文件索引
     * @return true 表示支持
     */
    boolean supports(MigrationTask task, MigrationFile file);

    /**
     * 解析单个源码文件。
     *
     * @param task     迁移任务
     * @param file     文件索引
     * @param filePath 文件绝对路径
     * @return 解析结果
     */
    ParseResult parse(MigrationTask task, MigrationFile file, Path filePath);
}
