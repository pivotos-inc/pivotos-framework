package com.pivotos.ai.kb.config;

import com.pivotos.starter.search.api.fusion.SearchRrf;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 知识库<b>全文召回通道</b>配置（S128，清偿 S122 遗留②）。
 * <p>与 {@link KbDocSearchProperties}（文档元数据列表检索）分工：
 * 那个管「管理面列表」，这个管「RAG 召回的关键词通道」。
 *
 * <p><b>默认值必须能直接跑</b>：不配任何 yml 时，全文通道走 search 抽象（simple 侧确定性 BM25、
 * es-java 侧引擎 BM25），融合用 k=60 等权 RRF，阈值 0（不裁剪）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Data
@ConfigurationProperties(prefix = "pivotos.search.kb-chunk")
public class KbChunkSearchProperties {

    /** 是否走搜索抽象（false → 全文通道只用库内 BM25） */
    private boolean enabled = true;

    /** 起服时若索引为空，是否从 ai_kb_chunk 表回灌 */
    private boolean bootstrapOnStart = true;

    /** 单次回灌上限（文本块数量远大于文档数，注意别把起服拖慢） */
    private int bootstrapMaxRows = 20000;

    /**
     * 参与打分的字段（对应 mapping 里被建为 text 的字段）。
     * <b>必须与建索引时一致</b>：指向 keyword 字段只会「整值命中」，拿不到 BM25。
     */
    private List<String> fields = new ArrayList<>(List.of("content"));

    /** 召回分数阈值（低于此值丢弃；0 = 不裁剪） */
    private double minScore = 0.0D;

    /**
     * 候选窗口（仅「本地重打分」退化路径生效，如 easy-es 实现；0 = 交给实现决定）
     */
    private int candidateWindow = 0;

    /**
     * 搜索通道不可用或为空时，是否回退到库内 BM25（{@code Bm25Retriever}）。
     * <b>默认 true</b>：召回是增强而非强依赖，索引冷启动未完成时不该让问答「搜不到」。
     */
    private boolean fallbackToDbBm25 = true;

    /** RRF 融合参数 */
    private Fusion fusion = new Fusion();

    /**
     * RRF 融合：只看名次不看分数，故向量相似度与 BM25 这种量纲不同的双通道无需归一化即可合并。
     */
    @Data
    public static class Fusion {

        /** RRF 常数 k（越大越削弱头部名次权重） */
        private int k = SearchRrf.DEFAULT_K;

        /** 向量通道权重 */
        private double vectorWeight = SearchRrf.DEFAULT_WEIGHT;

        /** 全文通道权重 */
        private double fullTextWeight = SearchRrf.DEFAULT_WEIGHT;

        /** 融合分阈值（0 = 不裁剪；调大可裁掉「只在通道尾部擦到」的弱候选） */
        private double minScore = SearchRrf.DEFAULT_MIN_SCORE;
    }
}
