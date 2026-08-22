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
 * YApi 平台适配器（自动同步）
 *
 * <p>YApi 支持 Swagger JSON 数据导入，通过项目 Token 认证。
 * 导入 API 端点：POST {server}/api/import/swagger
 * 官方文档：<a href="https://hellosean1025.github.io/yapi/documents/data.html">YApi 数据导入</a>
 */
@Slf4j
@Component
public class YApiSyncAdapter implements com.pivotos.docsync.adapter.DocSyncAdapter {

    @Override
    public DocSyncTypeEnum getType() {
        return DocSyncTypeEnum.YAPI;
    }

    @Override
    public boolean testConnection(DocSyncConfig config) {
        try {
            String url = config.getServerUrl() + "/api/user/status";
            String response = HttpUtil.get(url, 5000);
            JSONObject json = JSONUtil.parseObj(response);
            return json.getInt("errcode", -1) == 0;
        } catch (Exception e) {
            log.warn("[YApi] 连通性测试失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public SyncResultDTO sync(String openApiJson, DocSyncConfig config) {
        long start = System.currentTimeMillis();
        try {
            int apiCount = countApis(openApiJson);

            // YApi Swagger 导入接口
            String url = config.getServerUrl() + "/api/import/swagger";
            Map<String, Object> params = new HashMap<>();
            params.put("type", "swagger");
            params.put("token", config.getCredential());
            params.put("json", openApiJson);
            params.put("merge", "good"); // 智能合并模式
            params.put("catid", "0"); // 默认分类

            String body = JSONUtil.toJsonStr(params);
            String response = HttpUtil.post(url, body, 30000);
            JSONObject result = JSONUtil.parseObj(response);

            long elapsed = System.currentTimeMillis() - start;
            int errcode = result.getInt("errcode", -1);
            if (errcode == 0) {
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
                        .message("YApi 返回错误: " + result.getStr("errmsg"))
                        .elapsedMs(elapsed)
                        .syncTime(LocalDateTime.now())
                        .build();
            }
        } catch (Exception e) {
            log.error("[YApi] 同步失败", e);
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
                        "http://yapi.local:3000", "YApi 服务器部署地址"),
                new DocSyncConfigFieldDTO("credential", "项目 Token", true, "password",
                        "项目设置中的 token", "在 YApi 项目设置 → 数据同步中获取"));
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
