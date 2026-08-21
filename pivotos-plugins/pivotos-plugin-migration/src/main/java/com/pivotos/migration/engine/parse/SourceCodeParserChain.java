package com.pivotos.migration.engine.parse;

import com.pivotos.migration.domain.entity.MigrationFile;
import com.pivotos.migration.domain.entity.MigrationTask;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * 源码解析器链。
 */
@Component
@RequiredArgsConstructor
public class SourceCodeParserChain {

    private final List<SourceCodeParser> parsers;

    /**
     * 按顺序找到第一个支持的解析器进行解析。
     *
     * @param task     迁移任务
     * @param file     文件索引
     * @param filePath 文件绝对路径
     * @return 解析结果，未找到解析器返回空结果
     */
    public ParseResult parse(MigrationTask task, MigrationFile file, Path filePath) {
        for (SourceCodeParser parser : parsers) {
            if (parser.supports(task, file)) {
                return parser.parse(task, file, filePath);
            }
        }
        return new ParseResult();
    }
}
