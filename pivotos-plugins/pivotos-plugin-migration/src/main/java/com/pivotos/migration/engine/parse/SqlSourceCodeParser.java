package com.pivotos.migration.engine.parse;

import cn.hutool.core.io.FileUtil;
import com.alibaba.fastjson2.JSON;
import com.pivotos.migration.domain.entity.MigrationArtifact;
import com.pivotos.migration.domain.entity.MigrationFile;
import com.pivotos.migration.domain.entity.MigrationIrNode;
import com.pivotos.migration.domain.entity.MigrationTask;
import com.pivotos.migration.domain.enums.MigrationArtifactType;
import com.pivotos.migration.domain.enums.MigrationIrNodeType;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * SQL / Flyway 脚本解析器。
 */
@Order(30)
@Component
public class SqlSourceCodeParser implements SourceCodeParser {

    private static final Pattern TABLE_PATTERN = Pattern.compile(
            "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?[`\"\\[]?([\\w_]+)[`\"\\[]?",
            Pattern.CASE_INSENSITIVE);

    @Override
    public boolean supports(MigrationTask task, MigrationFile file) {
        String ext = file.getExtension();
        return "sql".equalsIgnoreCase(ext) || "ddl".equalsIgnoreCase(ext);
    }

    @Override
    public ParseResult parse(MigrationTask task, MigrationFile file, Path filePath) {
        ParseResult result = new ParseResult();
        String content = FileUtil.readString(filePath.toFile(), StandardCharsets.UTF_8);
        if (content.isBlank()) {
            result.addLog("SQL 文件内容为空：" + file.getRelativePath());
            return result;
        }

        java.util.List<String> tables = extractTables(content);
        for (String table : tables) {
            MigrationIrNode node = new MigrationIrNode();
            node.setTaskId(task.getId());
            node.setNodeType(MigrationIrNodeType.TABLE.getCode());
            node.setNodeId("table:" + table);
            node.setName(table);
            node.setSourcePath(file.getRelativePath());

            Map<String, Object> payload = new HashMap<>();
            payload.put("tableName", table);
            payload.put("statementCount", content.split(";").length);
            node.setPayload(JSON.toJSONString(payload));
            result.addIrNode(node);
        }

        MigrationArtifact artifact = new MigrationArtifact();
        artifact.setTaskId(task.getId());
        artifact.setArtifactType(MigrationArtifactType.FLYWAY.getCode());
        artifact.setRelativePath(file.getRelativePath());
        artifact.setContentHash(file.getContentHash());
        artifact.setOriginalContent(content.length() > 65535 ? content.substring(0, 65535) : content);
        result.addArtifact(artifact);

        result.addLog("解析 SQL 文件：" + file.getRelativePath() + "，发现 " + tables.size() + " 张表");
        return result;
    }

    private java.util.List<String> extractTables(String content) {
        java.util.List<String> tables = new java.util.ArrayList<>();
        Matcher m = TABLE_PATTERN.matcher(content);
        while (m.find()) {
            tables.add(m.group(1));
        }
        return tables;
    }
}
