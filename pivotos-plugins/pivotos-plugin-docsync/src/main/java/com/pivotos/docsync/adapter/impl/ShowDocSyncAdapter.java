package com.pivotos.docsync.adapter.impl;

import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.pivotos.docsync.api.dto.DocSyncConfigFieldDTO;
import com.pivotos.docsync.api.dto.SyncResultDTO;
import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import com.pivotos.docsync.api.enums.SyncStatusEnum;
import com.pivotos.docsync.converter.OpenApiMarkdownConverter;
import com.pivotos.docsync.entity.DocSyncConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ShowDoc 平台适配器（自动同步，需 OpenAPI→Markdown 转换）
 *
 * <p>ShowDoc 开放 API 仅接受 Markdown 格式内容，不支持直接导入 OpenAPI JSON。
 * 本适配器通过 {@link OpenApiMarkdownConverter} 将 OpenAPI JSON 转换为 Markdown，
 * 再通过 ShowDoc 的 updatePage 接口推送。
 * 官方文档：<a href="https://www.showdoc.com.cn/page/102098">ShowDoc 开放 API</a>
 *
 * <p>认证：api_key（项目标识）+ api_token（请求签名凭证）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShowDocSyncAdapter implements com.pivotos.docsync.adapter.DocSyncAdapter {

    private final OpenApiMarkdownConverter markdownConverter;

    @Override
    public DocSyncTypeEnum getType() {
        return DocSyncTypeEnum.SHOWDOC;
    }

    @Override
    public boolean testConnection(DocSyncConfig config) {
        try {
            String url = config.getServerUrl() + "/server/index.php?s=/api/open/getCatList";
            Map<String, Object> params = new HashMap<>();
            params.put("api_key", config.getCredential());
            params.put("api_token", config.getSecondaryCredential());
            String body = HttpUtil.toParams(params);
            String response = HttpUtil.post(url, body, 5000);
            JSONObject json = JSONUtil.parseObj(response);
            return json.getInt("error_code", -1) == 0;
        } catch (Exception e) {
            log.warn("[ShowDoc] 连通性测试失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public SyncResultDTO sync(String openApiJson, DocSyncConfig config) {
        long start = System.currentTimeMillis();
        try {
            // 1. 将 OpenAPI JSON 转换为 Markdown
            String markdown = markdownConverter.convert(openApiJson);

            // 2. 获取接口数量
            int apiCount = countApis(openApiJson);

            // 3. 推送 Markdown 到 ShowDoc
            String url = config.getServerUrl() + "/server/index.php?s=/api/open/updatePage";
            Map<String, Object> params = new HashMap<>();
            params.put("api_key", config.getCredential());
            params.put("api_token", config.getSecondaryCredential());
            params.put("page_title", "PivotOS API 文档");
            params.put("page_content", markdown);
            params.put("cat_name", "API 接口文档");

            String body = HttpUtil.toParams(params);
            String response = HttpUtil.post(url, body, 30000);
            JSONObject result = JSONUtil.parseObj(response);

            long elapsed = System.currentTimeMillis() - start;
            if (result.getInt("error_code", -1) == 0) {
                return SyncResultDTO.builder()
                        .status(SyncStatusEnum.SUCCESS)
                        .apiCount(apiCount)
                        .message("同步成功（已转换为 Markdown 格式推送）")
                        .elapsedMs(elapsed)
                        .syncTime(LocalDateTime.now())
                        .build();
            } else {
                return SyncResultDTO.builder()
                        .status(SyncStatusEnum.FAILED)
                        .apiCount(apiCount)
                        .message("ShowDoc 返回错误: " + result.getStr("error_message", "未知错误"))
                        .elapsedMs(elapsed)
                        .syncTime(LocalDateTime.now())
                        .build();
            }
        } catch (Exception e) {
            log.error("[ShowDoc] 同步失败", e);
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
                        "http://showdoc.local", "ShowDoc 服务器部署地址"),
                new DocSyncConfigFieldDTO("credential", "API Key", true, "password",
                        "项目 API Key", "在 ShowDoc 项目设置中获取"),
                new DocSyncConfigFieldDTO("secondaryCredential", "API Token", true, "password",
                        "项目 API Token", "在 ShowDoc 项目设置中获取"));
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
