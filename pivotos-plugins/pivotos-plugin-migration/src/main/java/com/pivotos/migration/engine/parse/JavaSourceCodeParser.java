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
 * Java 源码解析器。
 *
 * <p>基于正则做轻量 AST 抽取：包名、类名、注解、方法签名，并映射为 IR 节点与产物。
 */
@Order(10)
@Component
public class JavaSourceCodeParser implements SourceCodeParser {

    private static final String EXTENSION = "java";

    private static final Pattern PACKAGE_PATTERN = Pattern.compile("\\bpackage\\s+([\\w.]+);");
    private static final Pattern TYPE_PATTERN = Pattern.compile(
            "(?:@\\w+(?:\\([^)]*\\))?\\s*)*" +          // 注解
            "\\b(public\\s+)?(?:abstract\\s+)?\\b(class|interface|enum)\\s+(\\w+)");
    private static final Pattern METHOD_PATTERN = Pattern.compile(
            "\\b(public|private|protected)\\s+" +          // 访问修饰符
            "(?:static\\s+|final\\s+|abstract\\s+)*" +   // 其他修饰符
            "([<\\w\\s,?\\[\\]]+)\\s+" +                  // 返回类型
            "(\\w+)\\s*\\([^)]*\\)\\s*\\{");             // 方法名 + 参数

    @Override
    public boolean supports(MigrationTask task, MigrationFile file) {
        return EXTENSION.equalsIgnoreCase(file.getExtension());
    }

    @Override
    public ParseResult parse(MigrationTask task, MigrationFile file, Path filePath) {
        ParseResult result = new ParseResult();
        String content = FileUtil.readString(filePath.toFile(), StandardCharsets.UTF_8);
        if (content.isBlank()) {
            result.addLog("Java 文件内容为空：" + file.getRelativePath());
            return result;
        }

        String pkg = extractPackage(content);
        TypeInfo typeInfo = extractType(content);

        // 构建 IR 节点
        MigrationIrNode node = new MigrationIrNode();
        node.setTaskId(task.getId());
        node.setNodeType(detectNodeType(content, typeInfo.name()).getCode());
        node.setNodeId((pkg != null ? pkg + "." : "") + typeInfo.name());
        node.setName(typeInfo.name());
        node.setSourcePath(file.getRelativePath());

        Map<String, Object> payload = new HashMap<>();
        payload.put("package", pkg);
        payload.put("kind", typeInfo.kind());
        payload.put("methods", extractMethods(content));
        node.setPayload(JSON.toJSONString(payload));
        result.addIrNode(node);

        // 构建产物
        MigrationArtifact artifact = new MigrationArtifact();
        artifact.setTaskId(task.getId());
        artifact.setArtifactType(MigrationArtifactType.JAVA.getCode());
        artifact.setRelativePath(file.getRelativePath());
        artifact.setContentHash(file.getContentHash());
        artifact.setOriginalContent(content.length() > 65535 ? content.substring(0, 65535) : content);
        result.addArtifact(artifact);

        result.addLog("解析 Java 文件：" + file.getRelativePath() + " -> " + node.getNodeType());
        return result;
    }

    private String extractPackage(String content) {
        Matcher m = PACKAGE_PATTERN.matcher(content);
        return m.find() ? m.group(1) : null;
    }

    private TypeInfo extractType(String content) {
        Matcher m = TYPE_PATTERN.matcher(content);
        if (m.find()) {
            return new TypeInfo(m.group(2), m.group(3));
        }
        return new TypeInfo("class", "Unknown");
    }

    private MigrationIrNodeType detectNodeType(String content, String typeName) {
        if (content.contains("@Entity") || content.contains("@Table")) {
            return MigrationIrNodeType.ENTITY;
        }
        if (content.contains("@RestController") || content.contains("@Controller")) {
            return MigrationIrNodeType.ROUTE;
        }
        if (content.contains("@Service")) {
            return MigrationIrNodeType.SERVICE;
        }
        if (typeName.endsWith("Mapper") || typeName.endsWith("Repository")) {
            return MigrationIrNodeType.ENTITY;
        }
        return MigrationIrNodeType.MODULE;
    }

    private java.util.List<String> extractMethods(String content) {
        java.util.List<String> methods = new java.util.ArrayList<>();
        Matcher m = METHOD_PATTERN.matcher(content);
        while (m.find()) {
            methods.add(m.group(3) + "(): " + m.group(2).trim());
        }
        return methods;
    }

    private record TypeInfo(String kind, String name) {
    }
}
