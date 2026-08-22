package com.pivotos.docsync.adapter.impl;

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
 * Torna 平台适配器（自动同步）
 *
 * <p>Torna 提供 OpenAPI 推送接口，通过项目 Token 认证，
 * 支持 doc.push 接口创建/更新文档、分类、参数、调试环境等。
 * 官方文档：<a href="https://torna.cn/dev/openapi.html">https://torna.cn/dev/openapi.html</a>
 */
@Slf4j
@Component
public class TornaSyncAdapter implements com.pivotos.docsync.adapter.DocSyncAdapter {

    private static final String PUSH_PATH = "/api";

    @Override
    public DocSyncTypeEnum getType() {
        return DocSyncTypeEnum.TORNA;
    }

    @Override
    public boolean testConnection(DocSyncConfig config) {
        try {
            String url = config.getServerUrl() + "/api/system/ping";
            Map<String, Object> params = new HashMap<>();
            params.put("token", config.getCredential());
            String body = JSONUtil.toJsonStr(params);
            String response = HttpUtil.post(url, body, 5000);
            JSONObject json = JSONUtil.parseObj(response);
            return json.getInt("code", -1) == 0;
        } catch (Exception e) {
            log.warn("[Torna] 连通性测试失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public SyncResultDTO sync(String openApiJson, DocSyncConfig config) {
        long start = System.currentTimeMillis();
        try {
            JSONObject openApi = JSONUtil.parseObj(openApiJson);
            int apiCount = countApis(openApi);

            // 调用 Torna doc.push 接口推送文档
            String url = config.getServerUrl() + PUSH_PATH;
            Map<String, Object> request = new HashMap<>();
            request.put("token", config.getCredential());

            // Torna 的 doc.push 接受 OpenAPI 格式的推送
            Map<String, Object> pushData = new HashMap<>();
            pushData.put("openApi", openApiJson);
            request.put("data", pushData);

            String body = JSONUtil.toJsonStr(request);
            String response = HttpUtil.post(url, body, 30000);
            JSONObject result = JSONUtil.parseObj(response);

            long elapsed = System.currentTimeMillis() - start;
            if (result.getInt("code", -1) == 0) {
                return SyncResultDTO.builder()
                        .status(SyncStatusEnum.SUCCESS)
                        .apiCount(apiCount)
                        .message("同步成功")
                        .elapsedMs(elapsed)
                        .syncTime(LocalDateTime.now())
                        .build();
            } else {
                return SyncResultDTO.builder()
                        .status(SyncStatusEnum.FAILED)
                        .apiCount(apiCount)
                        .message("Torna 返回错误: " + result.getStr("msg"))
                        .elapsedMs(elapsed)
                        .syncTime(LocalDateTime.now())
                        .build();
            }
        } catch (Exception e) {
            log.error("[Torna] 同步失败", e);
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
                new DocSyncConfigFieldDTO("serverUrl", "服务器地址", true, "url",
                        "http://torna.local:7700", "Torna 服务器部署地址"),
                new DocSyncConfigFieldDTO("credential", "项目 Token", true, "password",
                        "在 Torna 项目设置中获取", "用于调用 Torna OpenAPI 推送接口"));
    }

    private int countApis(JSONObject openApi) {
        JSONObject paths = openApi.getJSONObject("paths");
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
    }
}
