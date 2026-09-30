package com.pivotos.ai.kb.search;

import com.pivotos.ai.kb.config.KbDocSearchProperties;
import com.pivotos.ai.kb.mapper.AiKbChunkMapper;
import com.pivotos.ai.kb.mapper.KbDocumentMapper;
import com.pivotos.ai.kb.mapper.KnowledgeBaseMapper;
import com.pivotos.ai.kb.service.KbPipelineService;
import com.pivotos.ai.kb.service.impl.KbDocumentServiceImpl;
import com.pivotos.starter.search.api.template.SearchTemplate;
import com.pivotos.starter.search.config.SearchAutoConfiguration;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 知识库文档检索链路测试上下文（S122，本插件首个 IT 基建）。
 *
 * <p><b>为什么不整插件起上下文</b>：ai-kb 的完整装配依赖 EmbeddingModel / VectorStore / OCR 原生库，
 * 与「文档列表检索」这条链路无关。这里只装配检索相关 Bean：
 * 真 {@code SearchAutoConfiguration}（simple 内存 Provider）+ 真 {@code KbDocumentServiceImpl} +
 * 真 {@link KbDocSearchSupport}，Mapper 与 RAG 管线用 Mockito 替身。
 * 检索条件树、实体 ↔ 文档映射、租户过滤、回灌逻辑全部是真代码。
 */
@Configuration(proxyBeanMethods = false)
@Import(SearchAutoConfiguration.class)
@EnableConfigurationProperties(KbDocSearchProperties.class)
public class KbDocSearchTestConfig {

    @Bean
    public KbDocSearchSupport kbDocSearchSupport(ObjectProvider<SearchTemplate> provider,
                                                 KbDocSearchProperties properties) {
        return new KbDocSearchSupport(provider, properties);
    }

    @Bean
    public KbDocIndexBootstrap kbDocIndexBootstrap(KbDocumentMapper documentMapper,
                                                   KbDocSearchSupport support) {
        return new KbDocIndexBootstrap(documentMapper, support);
    }

    @Bean
    public KbDocumentServiceImpl kbDocumentService(KbDocumentMapper documentMapper,
                                                   KnowledgeBaseMapper knowledgeBaseMapper,
                                                   AiKbChunkMapper chunkMapper,
                                                   KbPipelineService pipelineService,
                                                   KbDocSearchSupport support) {
        return new KbDocumentServiceImpl(documentMapper, knowledgeBaseMapper, chunkMapper,
                pipelineService, support);
    }

    @Bean
    public KbDocumentMapper kbDocumentMapper() {
        return Mockito.mock(KbDocumentMapper.class);
    }

    @Bean
    public KnowledgeBaseMapper knowledgeBaseMapper() {
        return Mockito.mock(KnowledgeBaseMapper.class);
    }

    @Bean
    public AiKbChunkMapper aiKbChunkMapper() {
        return Mockito.mock(AiKbChunkMapper.class);
    }

    @Bean
    public KbPipelineService kbPipelineService() {
        return Mockito.mock(KbPipelineService.class);
    }
}
