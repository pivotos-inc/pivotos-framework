package com.pivotos.ai.service;

import com.pivotos.ai.domain.dto.ApiKeySaveRequest;
import com.pivotos.ai.domain.dto.ProviderSaveRequest;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.entity.AiProvider;
import com.pivotos.ai.domain.vo.ApiKeyVO;
import com.pivotos.ai.domain.vo.ProviderOptionVO;
import com.pivotos.ai.domain.vo.ProviderVO;

import java.util.List;

/** AI 供应商与 API Key 管理服务 */
public interface AiProviderService {

    // ---------- 供应商管理 ----------

    /** 供应商列表（管理页，含停用，附启用 Key 数） */
    List<ProviderVO> listProviders();

    /** 新增供应商 */
    Long createProvider(ProviderSaveRequest request);

    /** 修改供应商 */
    void updateProvider(ProviderSaveRequest request);

    /** 删除供应商（级联删除其 Key） */
    void deleteProvider(Long id);

    // ---------- 对话侧只读 ----------

    /** 启用供应商选项（对话页下拉，按 sort 正序；租户自有配置优先 → 平台兜底） */
    List<ProviderOptionVO> listOptions();

    /** 供应商可用模型（OpenAI 兼容 /models，5 分钟缓存） */
    List<String> listModels(Long providerId);

    // ---------- 向量化侧只读（S61 动态 Embedding Key） ----------

    /** 默认向量化供应商：租户自有启用配置优先，无则平台兜底；取 sort 最靠前且有 embedding/all 用途启用 Key 者，无则 null */
    AiProvider findEmbeddingProvider();

    /** 供应商启用中且用途匹配 embedding/all 的 Key 列表（解密实体，仅供调用链内部使用） */
    List<AiApiKey> listActiveEmbeddingKeys(Long providerId);

    /** 失效动态 EmbeddingModel 委托缓存（Key/供应商变更时由管理链路调用） */
    void evictEmbeddingModel();

    // ---------- 重排侧只读（S65 reranker 重排） ----------

    /** 重排供应商：租户自有启用配置优先，无则平台兜底；取 sort 最靠前且 rerankModel 非空、有 embedding/all 用途启用 Key 者，无则 null */
    AiProvider findRerankProvider();

    /** 校验并返回启用中的供应商（不存在/停用/非当前租户且非平台 → 5021） */
    AiProvider requireActiveProvider(Long providerId);

    /** 默认供应商：租户自有启用配置优先，无则平台兜底；取 sort 最靠前且有启用 Key 者，无则 null */
    AiProvider findDefaultProvider();

    /** 供应商启用中的 Key 列表（解密实体，仅供调用链内部使用，严禁出接口） */
    List<AiApiKey> listActiveKeys(Long providerId);

    // ---------- Key 健康度 ----------

    /** 记一次 Key 调用失败：连续失败计数 +1，达阈值原子停用并站内信告警（回调线程可调，内部兼容无上下文） */
    void recordKeyFailure(Long keyId);

    /** 记一次 Key 调用成功：连续失败计数清零 */
    void recordKeySuccess(Long keyId);

    // ---------- Key 管理 ----------

    /** Key 列表（脱敏） */
    List<ApiKeyVO> listKeys(Long providerId);

    /** 新增 Key（明文必填，AES 落库） */
    Long createKey(ApiKeySaveRequest request);

    /** 修改 Key（label/status；apiKey 留空不变更） */
    void updateKey(ApiKeySaveRequest request);

    /** 删除 Key */
    void deleteKey(Long id);
}
