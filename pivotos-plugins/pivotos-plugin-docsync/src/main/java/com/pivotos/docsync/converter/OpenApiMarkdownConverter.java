package com.pivotos.docsync.converter;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.springframework.stereotype.Component;

/**
 * OpenAPI JSON → Markdown 转换器
 *
 * <p>将 SpringDoc 生成的 OpenAPI 3.0 JSON 转换为 Markdown 格式，
 * 供 ShowDoc 等仅接受 Markdown 内容的平台使用。
 */
@Component
public class OpenApiMarkdownConverter {

    /**
     * 将 OpenAPI JSON 转换为 Markdown 文档
     *
     * @param openApiJson OpenAPI 3.0 JSON 字符串
     * @return Markdown 格式的 API 文档
     */
    public String convert(String openApiJson) {
        JSONObject openApi = JSONUtil.parseObj(openApiJson);
        StringBuilder md = new StringBuilder();

        // 标题与基础信息
        JSONObject info = openApi.getJSONObject("info");
        String title = info != null ? info.getStr("title", "API 文档") : "API 文档";
        String version = info != null ? info.getStr("version", "") : "";
        md.append("# ").append(title);
        if (!version.isEmpty()) {
            md.append(" v").append(version);
        }
        md.append("\n\n");

        if (info != null && info.getStr("description") != null) {
            md.append(info.getStr("description")).append("\n\n");
        }

        // 服务器地址
        JSONArray servers = openApi.getJSONArray("servers");
        if (servers != null && !servers.isEmpty()) {
            md.append("## 服务器地址\n\n");
            for (int i = 0; i < servers.size(); i++) {
                JSONObject server = servers.getJSONObject(i);
                md.append("- `").append(server.getStr("url")).append("`");
                if (server.getStr("description") != null) {
                    md.append(" — ").append(server.getStr("description"));
                }
                md.append("\n");
            }
            md.append("\n");
        }

        // 接口列表
        JSONObject paths = openApi.getJSONObject("paths");
        if (paths == null || paths.isEmpty()) {
            md.append("暂无接口数据\n");
            return md.toString();
        }

        md.append("## 接口列表\n\n");
        int index = 1;
        for (String path : paths.keySet()) {
            JSONObject pathItem = paths.getJSONObject(path);
            if (pathItem == null) {
                continue;
            }
            for (String method : pathItem.keySet()) {
                JSONObject operation = pathItem.getJSONObject(method);
                if (operation == null) {
                    continue;
                }
                convertOperation(md, index++, path, method, operation, openApi);
            }
        }

        return md.toString();
    }

    private void convertOperation(StringBuilder md, int index, String path, String method,
                                  JSONObject operation, JSONObject openApi) {
        String summary = operation.getStr("summary", "");
        String description = operation.getStr("description", "");

        md.append("### ").append(index).append(". ");
        md.append("**").append(method.toUpperCase()).append("** `").append(path).append("`");
        if (!summary.isEmpty()) {
            md.append(" — ").append(summary);
        }
        md.append("\n\n");

        if (!description.isEmpty()) {
            md.append(description).append("\n\n");
        }

        // 请求参数
        JSONArray parameters = operation.getJSONArray("parameters");
        if (parameters != null && !parameters.isEmpty()) {
            md.append("**请求参数**\n\n");
            md.append("| 参数名 | 位置 | 类型 | 必填 | 描述 |\n");
            md.append("|--------|------|------|------|------|\n");
            for (int i = 0; i < parameters.size(); i++) {
                JSONObject param = parameters.getJSONObject(i);
                String name = param.getStr("name", "");
                String in = param.getStr("in", "");
                Boolean required = param.getBool("required", false);
                JSONObject schema = param.getJSONObject("schema");
                String type = schema != null ? schema.getStr("type", "") : "";
                String desc = param.getStr("description", "");
                md.append("| ").append(name)
                        .append(" | ").append(in)
                        .append(" | ").append(type)
                        .append(" | ").append(required ? "是" : "否")
                        .append(" | ").append(desc).append(" |\n");
            }
            md.append("\n");
        }

        // 请求体
        JSONObject requestBody = operation.getJSONObject("requestBody");
        if (requestBody != null) {
            JSONObject content = requestBody.getJSONObject("content");
            if (content != null) {
                JSONObject jsonContent = content.getJSONObject("application/json");
                if (jsonContent != null) {
                    JSONObject schema = jsonContent.getJSONObject("schema");
                    if (schema != null) {
                        md.append("**请求体**\n\n```json\n");
                        md.append(generateSchemaExample(schema, openApi));
                        md.append("\n```\n\n");
                    }
                }
            }
        }

        // 响应
        JSONObject responses = operation.getJSONObject("responses");
        if (responses != null && !responses.isEmpty()) {
            md.append("**响应结果**\n\n");
            for (String code : responses.keySet()) {
                JSONObject response = responses.getJSONObject(code);
                String desc = response != null ? response.getStr("description", "") : "";
                md.append("- ").append(code).append(": ").append(desc).append("\n");
            }
            md.append("\n");
        }

        md.append("---\n\n");
    }

    /**
     * 根据 Schema 生成示例 JSON
     */
    private String generateSchemaExample(JSONObject schema, JSONObject openApi) {
        if (schema == null) {
            return "{}";
        }
        String ref = schema.getStr("$ref");
        if (ref != null) {
            // 解析 $ref 引用
            String refName = ref.substring(ref.lastIndexOf('/') + 1);
            JSONObject components = openApi.getJSONObject("components");
            if (components != null) {
                JSONObject schemas = components.getJSONObject("schemas");
                if (schemas != null) {
                    JSONObject refSchema = schemas.getJSONObject(refName);
                    if (refSchema != null) {
                        return generateSchemaExample(refSchema, openApi);
                    }
                }
            }
            return "{}";
        }

        String type = schema.getStr("type", "object");
        return switch (type) {
            case "object" -> {
                JSONObject properties = schema.getJSONObject("properties");
                if (properties == null) {
                    yield "{}";
                }
                StringBuilder sb = new StringBuilder("{\n");
                int i = 0;
                for (String key : properties.keySet()) {
                    JSONObject prop = properties.getJSONObject(key);
                    sb.append("  \"").append(key).append("\": ");
                    sb.append(generateSchemaExample(prop, openApi));
                    if (i < properties.size() - 1) {
                        sb.append(",");
                    }
                    sb.append("\n");
                    i++;
                }
                sb.append("}");
                yield sb.toString();
            }
            case "array" -> {
                JSONObject items = schema.getJSONObject("items");
                if (items != null) {
                    yield "[" + generateSchemaExample(items, openApi) + "]";
                }
                yield "[]";
            }
            case "string" -> "\"\"";
            case "integer", "number" -> "0";
            case "boolean" -> "false";
            default -> "null";
        };
    }
}
