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
 * Vue 单文件解析器。
 */
@Order(20)
@Component
public class VueSourceCodeParser implements SourceCodeParser {

    private static final String EXTENSION = "vue";

    private static final Pattern NAME_PATTERN = Pattern.compile("(?:export\\s+default\\s*\\{[^}]*name\\s*:\\s*['\"])([^'\"]+)");
    private static final Pattern ROUTE_PATTERN = Pattern.compile("(?:path|name)\\s*:\\s*['\"]([^'\"]+)['\"]");

    @Override
    public boolean supports(MigrationTask task, MigrationFile file) {
        return EXTENSION.equalsIgnoreCase(file.getExtension());
    }

    @Override
    public ParseResult parse(MigrationTask task, MigrationFile file, Path filePath) {
        ParseResult result = new ParseResult();
        String content = FileUtil.readString(filePath.toFile(), StandardCharsets.UTF_8);
        if (content.isBlank()) {
            result.addLog("Vue 文件内容为空：" + file.getRelativePath());
            return result;
        }

        String componentName = extractComponentName(content, file.getFileName());

        MigrationIrNode node = new MigrationIrNode();
        node.setTaskId(task.getId());
        node.setNodeType(isPage(file.getRelativePath()) ? MigrationIrNodeType.PAGE.getCode() : MigrationIrNodeType.COMPONENT.getCode());
        node.setNodeId(file.getRelativePath());
        node.setName(componentName);
        node.setSourcePath(file.getRelativePath());

        Map<String, Object> payload = new HashMap<>();
        payload.put("scriptLength", content.contains("<script") ? content.indexOf("</script>") - content.indexOf("<script") : 0);
        payload.put("routeHints", extractRouteHints(content));
        node.setPayload(JSON.toJSONString(payload));
        result.addIrNode(node);

        MigrationArtifact artifact = new MigrationArtifact();
        artifact.setTaskId(task.getId());
        artifact.setArtifactType(MigrationArtifactType.VUE.getCode());
        artifact.setRelativePath(file.getRelativePath());
        artifact.setContentHash(file.getContentHash());
        artifact.setOriginalContent(content.length() > 65535 ? content.substring(0, 65535) : content);
        result.addArtifact(artifact);

        result.addLog("解析 Vue 文件：" + file.getRelativePath() + " -> " + node.getNodeType());
        return result;
    }

    private String extractComponentName(String content, String fileName) {
        Matcher m = NAME_PATTERN.matcher(content);
        if (m.find()) {
            return m.group(1);
        }
        String base = fileName.replace(".vue", "");
        return base.substring(0, 1).toUpperCase() + base.substring(1);
    }

    private boolean isPage(String relativePath) {
        String lower = relativePath.toLowerCase();
        return lower.contains("/pages/") || lower.contains("/views/");
    }

    private java.util.List<String> extractRouteHints(String content) {
        java.util.List<String> hints = new java.util.ArrayList<>();
        Matcher m = ROUTE_PATTERN.matcher(content);
        while (m.find()) {
            hints.add(m.group(1));
        }
        return hints;
    }
}
