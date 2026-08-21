package com.pivotos.migration.engine.parse;

import cn.hutool.core.io.FileUtil;
import com.pivotos.migration.domain.entity.MigrationArtifact;
import com.pivotos.migration.domain.entity.MigrationFile;
import com.pivotos.migration.domain.entity.MigrationTask;
import com.pivotos.migration.domain.enums.MigrationArtifactType;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 默认源码解析器，处理其他文本配置文件。
 */
@Order(Ordered.LOWEST_PRECEDENCE)
@Component
public class DefaultSourceCodeParser implements SourceCodeParser {

    /** 内容写入最大字符数：512 KB，超出部分截断（LONGTEXT 支持 4 GB，但过大内容无意义） */
    private static final int MAX_CONTENT_CHARS = 512 * 1024;

    /** 噪音目录前缀：IDE 配置、依赖、构建产物等，不存内容，仅建索引 */
    private static final String[] NOISE_PATH_SEGMENTS = {
            ".idea/", ".idea\\", "node_modules/", "node_modules\\",
            ".git/",  ".git\\",  "target/",       "dist/",
            "build/",  ".gradle/"
    };

    @Override
    public boolean supports(MigrationTask task, MigrationFile file) {
        String ext = file.getExtension();
        if (ext == null) {
            return false;
        }
        String lower = ext.toLowerCase();
        return lower.matches("txt|md|json|xml|yaml|yml|properties|conf|gradle|sh|bat|dockerfile|vue|js|ts|jsx|tsx|css|scss|less|html|htm");
    }

    @Override
    public ParseResult parse(MigrationTask task, MigrationFile file, Path filePath) {
        ParseResult result = new ParseResult();

        MigrationArtifact artifact = new MigrationArtifact();
        artifact.setTaskId(task.getId());
        artifact.setArtifactType(MigrationArtifactType.OTHER.getCode());
        artifact.setRelativePath(file.getRelativePath());
        artifact.setContentHash(file.getContentHash());

        // 噪音目录下的文件不存内容，仅建索引
        if (!isNoiseFile(file.getRelativePath())) {
            try {
                long fileSize = Files.size(filePath);
                if (fileSize > 0) {
                    String content = FileUtil.readString(filePath.toFile(), StandardCharsets.UTF_8);
                    if (content.length() > MAX_CONTENT_CHARS) {
                        artifact.setOriginalContent(content.substring(0, MAX_CONTENT_CHARS));
                    } else {
                        artifact.setOriginalContent(content);
                    }
                }
            } catch (Exception e) {
                // 读取失败（二进制文件等），仅记录路径
                result.addLog("读取文件内容失败，跳过内容存储：" + file.getRelativePath());
            }
        }

        result.addArtifact(artifact);
        result.addLog("默认解析文件：" + file.getRelativePath());
        return result;
    }

    private boolean isNoiseFile(String relativePath) {
        if (relativePath == null) {
            return false;
        }
        for (String seg : NOISE_PATH_SEGMENTS) {
            if (relativePath.contains(seg)) {
                return true;
            }
        }
        return false;
    }
}
