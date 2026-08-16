package com.pivotos.docsync.adapter.impl;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.pivotos.docsync.api.dto.DocSyncConfigFieldDTO;
import com.pivotos.docsync.api.dto.SyncResultDTO;
import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import com.pivotos.docsync.api.enums.SyncStatusEnum;
import com.pivotos.docsync.entity.DocSyncConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Apifox 平台适配器（自动同步，仅云端）
 *
 * <p>Apifox 开放 API 支持通过 REST 接口导入 OpenAPI/Swagger 数据。
 * 端点：POST https://api.apifox.com/v1/projects/{projectId}/import-openapi
 * 认证：Bearer Token（个人访问令牌）
 * 官方文档：<a href="https://apifox-openapi.apifox.cn/">Apifox 开放 API</a>
 *
 * <p>注意：Apifox 开放 API 仅支持云端版本，无法用于私有化部署。
 */
@Slf4j
@Component
public class ApifoxSyncAdapter implements com.pivotos.docsync.adapter.DocSyncAdapter {

    private static final String API_BASE = "https://api.apifox.com/v1/projects";
    private static final String API_VERSION = "2024-03-28";

    @Override
    public DocSyncTypeEnum getType() {
        return DocSyncTypeEnum.APIFOX;
    }

    @Override
    public boolean testConnection(DocSyncConfig config) {
        try {
            // Apifox 开放 API 仅 import-openapi 端点可用（GET 项目/列表均 302）。
            // 用空 paths + IGNORE 模式调用 import-openapi 验证 Token + ProjectId，不产生任何变更。
            String url = API_BASE + "/" + config.getProjectId() + "/import-openapi";
            String minimalSpec = "{\"openapi\":\"3.0.1\",\"info\":{\"title\":\"connectivity-test\",\"version\":\"0\"},\"paths\":{}}";
            Map<String, Object> input = new HashMap<>();
            input.put("input", minimalSpec);
            Map<String, Object> options = new HashMap<>();
            // KEEP_EXISTING：跳过所有变更，仅用于验证 Token + ProjectId 有效性
            options.put("endpointOverwriteBehavior", "KEEP_EXISTING");
            input.put("options", options);

            cn.hutool.http.HttpResponse response = HttpRequest.post(url)
                    .header("X-Apifox-Api-Version", API_VERSION)
                    .header("Authorization", "Bearer " + config.getCredential())
                    .header("Content-Type", "application/json")
                    .body(JSONUtil.toJsonStr(input))
                    .timeout(5000)
                    .execute();
            if (response.isOk()) {
                return true;
            }
            int status = response.getStatus();
            String body = response.body();
            log.warn("[Apifox] 连通性测试失败: HTTP {} - {}", status, body != null && body.length() > 200 ? body.substring(0, 200) : body);
            return false;
        } catch (Exception e) {
            log.warn("[Apifox] 连通性测试失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public SyncResultDTO sync(String openApiJson, DocSyncConfig config) {
        long start = System.currentTimeMillis();
        try {
            int apiCount = countApis(openApiJson);

            String url = API_BASE + "/" + config.getProjectId() + "/import-openapi";

            // 构建请求体：input 为 OpenAPI JSON 字符串
            Map<String, Object> input = new HashMap<>();
            input.put("input", openApiJson);

            // Apifox 官方枚举：OVERWRITE_EXISTING（覆盖匹配接口）/ AUTO_MERGE / KEEP_EXISTING / CREATE_NEW
            // 采用 OVERWRITE_EXISTING：首次同步全部新建，再次同步时按 method+path 匹配并覆盖为最新文档（中文注解）
            Map<String, Object> options = new HashMap<>();
            options.put("endpointOverwriteBehavior", "OVERWRITE_EXISTING");
            options.put("schemaOverwriteBehavior", "OVERWRITE_EXISTING");
            options.put("updateFolderOfChangedEndpoint", false);
            input.put("options", options);

            String body = JSONUtil.toJsonStr(input);
            String response = HttpRequest.post(url)
                    .header("X-Apifox-Api-Version", API_VERSION)
                    .header("Authorization", "Bearer " + config.getCredential())
                    .header("Content-Type", "application/json")
                    .body(body)
                    .timeout(30000)
                    .execute()
                    .body();

            long elapsed = System.currentTimeMillis() - start;

            // 解析 Apifox 响应（支持 success / errorMessage / errors 三种错误字段）
            JSONObject result = JSONUtil.parseObj(response);
            JSONObject data = result.getJSONObject("data");
            if (data != null) {
                JSONObject counters = data.getJSONObject("counters");
                int created = counters != null ? counters.getInt("endpointCreated", 0) : 0;
                int updated = counters != null ? counters.getInt("endpointUpdated", 0) : 0;
                return SyncResultDTO.builder()
                        .status(SyncStatusEnum.SUCCESS)
                        .apiCount(apiCount)
                        .message(String.format("同步成功（新建 %d，更新 %d）", created, updated))
                        .elapsedMs(elapsed)
                        .syncTime(LocalDateTime.now())
                        .build();
            } else {
                // Apifox 可能返回 errorMessage / message / errors 三种错误字段
                String errorMsg = result.getStr("errorMessage",
                        result.getStr("message",
                                result.getStr("errors", "未知错误")));
                log.warn("[Apifox] 同步返回失败，完整响应: {}", response.length() > 500 ? response.substring(0, 500) : response);
                return SyncResultDTO.builder()
                        .status(SyncStatusEnum.FAILED)
                        .apiCount(apiCount)
                        .message("Apifox 返回错误: " + errorMsg)
                        .elapsedMs(elapsed)
                        .syncTime(LocalDateTime.now())
                        .build();
            }
        } catch (Exception e) {
            log.error("[Apifox] 同步失败", e);
            return SyncResultDTO.builder()
                    .status(SyncStatusEnum.FAILED)
                    .apiCount(0)
                    .message("同步异常: " + e.getMessage())
                    .elapsedMs(System.currentTimeMillis() - start)
                    .syncTime(LocalDateTime.now())
                    .build();
        }
    }

    @Override
    public List<DocSyncConfigFieldDTO> getConfigFields() {
        return List.of(
                new DocSyncConfigFieldDTO("projectId", "项目 ID", true, "text",
                        "123456", "Apifox 项目的数字 ID（URL 路径中的数字部分）"),
                new DocSyncConfigFieldDTO("credential", "访问令牌", true, "password",
                        "Apifox 个人访问令牌", "在 Apifox 个人设置 → API 访问令牌中创建"));
    }

    @Override
    public String getManagementUrl(DocSyncConfig config) {
        return "https://app.apifox.com/project/" + config.getProjectId();
    }

    private int countApis(String openApiJson) {
        try {
            JSONObject json = JSONUtil.parseObj(openApiJson);
            JSONObject paths = json.getJSONObject("paths");
            if (paths == null) {
                return 0;
            }
            int count = 0;
            for (String pathKey : paths.keySet()) {
                JSONObject pathItem = paths.getJSONObject(pathKey);
                if (pathItem != null) {
                    count += pathItem.size();
                }
            }
            return count;
        } catch (Exception e) {
            return 0;
        }
    }
}
