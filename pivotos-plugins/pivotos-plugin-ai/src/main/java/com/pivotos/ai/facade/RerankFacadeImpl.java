package com.pivotos.ai.facade;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.ai.api.facade.IRerankFacade;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.service.AiProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * IRerankFacade 本地实现（S65，单体形态）：调用 DashScope OpenAI 兼容 reranks 协议
 * （{@code POST {scheme}://{host}/compatible-api/v1/reranks}，qwen3-rerank 等模型）。
 *
 * <p>配置来源：{@link AiProviderService#findRerankProvider()}（rerankModel 非空 +
 * embedding/all 用途启用 Key），与动态 EmbeddingModel 同一套数据库驱动体系。
 *
 * <p>降级约定：无配置/网络异常/4xx/5xx 一律 log.warn 并返回空列表，
 * 调用方（KB 检索管线）保持原召回顺序，不阻断主流程；Key 健康度接入失败计数。
 */
@Slf4j
@Component
public class RerankFacadeImpl implements IRerankFacade {

    /** reranks 协议固定路径（baseUrl 中的 /compatible-mode/v1 等路径段不参与拼接） */
    private static final String RERANK_PATH = "/compatible-api/v1/reranks";

    private final AiProviderService providerService;

    /** 连接/读取超时 10s：重排在检索链路尾部，超时即降级，不拖垮检索 */
    private final RestClient restClient;

    public RerankFacadeImpl(AiProviderService providerService) {
        this.providerService = providerService;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(10_000);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public List<RerankResult> rerank(String query, List<String> documents, int topN) {
        if (query == null || query.isBlank() || documents == null || documents.isEmpty()) {
            return List.of();
        }
        AiProvider provider = providerService.findRerankProvider();
        if (provider == null) {
            log.debug("[PivotOS] 未配置重排供应商（rerankModel 为空或无可用 Key），跳过重排");
            return List.of();
        }
        List<AiApiKey> keys = providerService.listActiveEmbeddingKeys(provider.getId());
        if (keys.isEmpty()) {
            return List.of();
        }
        AiApiKey key = keys.get(0);
        try {
            String endpoint = resolveRerankEndpoint(provider.getBaseUrl());
            JSONObject body = new JSONObject();
            body.put("model", provider.getRerankModel().strip());
            body.put("query", query);
            body.put("documents", documents);
            body.put("top_n", topN);
            String resp = restClient.post()
                    .uri(endpoint)
                    .header("Authorization", "Bearer " + key.getApiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body.toJSONString())
                    .retrieve()
                    .body(String.class);
            providerService.recordKeySuccess(key.getId());
            return parseResults(resp);
        } catch (Exception e) {
            providerService.recordKeyFailure(key.getId());
            log.warn("[PivotOS] rerank 调用失败（providerId={} model={}），降级为原召回顺序: {}",
                    provider.getId(), provider.getRerankModel(), e.getMessage());
            return List.of();
        }
    }

    /** 解析响应顶层 results[{index, relevance_score}]，按分数降序返回 */
    private List<RerankResult> parseResults(String resp) {
        if (resp == null || resp.isBlank()) {
            return List.of();
        }
        JSONArray results = JSON.parseObject(resp).getJSONArray("results");
        if (results == null || results.isEmpty()) {
            log.warn("[PivotOS] rerank 响应无 results，降级为原召回顺序");
            return List.of();
        }
        List<RerankResult> out = new ArrayList<>(results.size());
        for (int i = 0; i < results.size(); i++) {
            JSONObject item = results.getJSONObject(i);
            out.add(new RerankResult(item.getIntValue("index"), item.getDoubleValue("relevance_score")));
        }
        out.sort(Comparator.comparingDouble(RerankResult::score).reversed());
        return out;
    }

    /** baseUrl（须含 /v1 路径）→ 截取 scheme+host 拼 reranks 固定路径 */
    private String resolveRerankEndpoint(String baseUrl) {
        URI uri = URI.create(baseUrl.strip());
        return uri.getScheme() + "://" + uri.getAuthority() + RERANK_PATH;
    }
}
