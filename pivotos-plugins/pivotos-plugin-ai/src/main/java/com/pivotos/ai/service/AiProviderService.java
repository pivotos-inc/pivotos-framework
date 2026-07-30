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

    /** 启用供应商选项（对话页下拉，按 sort 正序） */
    List<ProviderOptionVO> listOptions();

    /** 供应商可用模型（OpenAI 兼容 /models，5 分钟缓存） */
    List<String> listModels(Long providerId);

    /** 校验并返回启用中的供应商（不存在/停用 → 5021） */
    AiProvider requireActiveProvider(Long providerId);

    /** 默认供应商：启用中 sort 最靠前且有启用 Key 者，无则 null（回落静态 ChatClient） */
    AiProvider findDefaultProvider();

    /** 供应商启用中的 Key 列表（解密实体，仅供调用链内部使用，严禁出接口） */
    List<AiApiKey> listActiveKeys(Long providerId);

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
