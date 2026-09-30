package com.pivotos.ai.kb.search;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.ai.kb.config.KbDocSearchProperties;
import com.pivotos.ai.kb.domain.dto.KbDocPageQuery;
import com.pivotos.ai.kb.domain.dto.KbDocUploadRequest;
import com.pivotos.ai.kb.domain.entity.KbDocument;
import com.pivotos.ai.kb.domain.entity.KnowledgeBase;
import com.pivotos.ai.kb.domain.vo.KbDocumentVO;
import com.pivotos.ai.kb.mapper.KbDocumentMapper;
import com.pivotos.ai.kb.mapper.KnowledgeBaseMapper;
import com.pivotos.ai.kb.service.KbDocumentService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.search.simple.SimpleSearchProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 知识库文档列表检索链路（S122）：
 * 写侧双写、条件映射、<b>租户隔离</b>、删除/重建同步、冷启动回灌、开关回退。
 */
@SpringBootTest(classes = KbDocSearchTestConfig.class)
class KbDocSearchIntegrationTest {

    private static final Long KB_ID = 77L;

    @Autowired
    private KbDocumentService documentService;

    @Autowired
    private KbDocSearchSupport support;

    @Autowired
    private KbDocIndexBootstrap bootstrap;

    @Autowired
    private KbDocSearchProperties properties;

    @Autowired
    private KbDocumentMapper documentMapper;

    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Autowired
    private SimpleSearchProvider provider;

    private final AtomicLong idSeq = new AtomicLong(1000);

    @BeforeEach
    void setUp() {
        provider.clear();
        Mockito.reset(documentMapper, knowledgeBaseMapper);
        properties.setEnabled(true);
        properties.setBootstrapOnStart(true);
        properties.setBootstrapMaxRows(20000);

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(KB_ID);
        kb.setChunkSize(500);
        kb.setChunkOverlap(50);
        Mockito.when(knowledgeBaseMapper.selectById(KB_ID)).thenReturn(kb);
        // MP insert 会给实体回填主键，这里模拟回填
        Mockito.doAnswer(inv -> {
            KbDocument doc = inv.getArgument(0);
            doc.setId(idSeq.incrementAndGet());
            doc.setCreateTime(LocalDateTime.now());
            return 1;
        }).when(documentMapper).insert(Mockito.any(KbDocument.class));
    }

    @AfterEach
    void tearDown() {
        provider.clear();
    }

    @Test
    void shouldIndexOnUploadAndBeSearchable() {
        Long id = upload("制度手册.pdf", 2);
        assertTrue(support.enabled());
        assertEquals(1, support.countIndexed());

        KbDocPageQuery query = new KbDocPageQuery();
        query.setKbId(KB_ID);
        query.setFileName("制度");
        PageResult<KbDocumentVO> page = documentService.page(query);
        assertEquals(1, page.getTotal());
        assertEquals(id, page.getList().get(0).getId());
        assertEquals("制度手册.pdf", page.getList().get(0).getFileName());
    }

    @Test
    void shouldMapKbIdFileNameAndStatusConditions() {
        upload("制度手册.pdf", 2);
        upload("操作手册.pdf", 1);
        upload("报销模板.xlsx", 2);

        assertEquals(3, query(q -> q.setKbId(KB_ID)).getTotal());
        assertEquals(1, query(q -> {
            q.setKbId(KB_ID);
            q.setStatus(1);
        }).getTotal());
        assertEquals(2, query(q -> {
            q.setKbId(KB_ID);
            q.setFileName("手册");
        }).getTotal());
        // 其他知识库的文档不得串进来
        assertEquals(0, query(q -> q.setKbId(999L)).getTotal());
    }

    @Test
    void shouldIsolateByTenantContext() throws Exception {
        KbDocument t5 = newDoc("租户5文档.pdf", 2);
        t5.setTenantId(5L);
        KbDocument t6 = newDoc("租户6文档.pdf", 2);
        t6.setTenantId(6L);
        support.indexBatch(List.of(t5, t6));

        // 有租户上下文 → 按值过滤（DB 路径由 MP 拦截器保证，检索路径必须显式补，否则跨租户泄漏）
        Long only5 = ScopedValue.where(TenantContext.KEY, 5L).call(() -> {
            KbDocPageQuery q = new KbDocPageQuery();
            q.setKbId(KB_ID);
            PageResult<KbDocumentVO> page = documentService.page(q);
            assertEquals(1, page.getTotal());
            return page.getList().get(0).getId();
        });
        assertEquals(t5.getId(), only5, "只能看到本租户文档");

        // 无租户上下文（单租户/平台侧，本测试线程天然未绑定）→ 与 MP 拦截器的「无上下文放行」语义一致
        assertFalse(TenantContext.isBound());
        KbDocPageQuery all = new KbDocPageQuery();
        all.setKbId(KB_ID);
        assertEquals(2, documentService.page(all).getTotal());
    }

    @Test
    void shouldRemoveIndexOnDelete() {
        Long id = upload("待删除.pdf", 2);
        assertEquals(1, support.countIndexed());

        Mockito.when(documentMapper.selectById(id)).thenReturn(dummyDoc(id, "待删除.pdf", 2));
        documentService.delete(id);

        KbDocPageQuery query = new KbDocPageQuery();
        query.setKbId(KB_ID);
        assertEquals(0, documentService.page(query).getTotal());
    }

    @Test
    void shouldRefreshIndexOnReindex() {
        Long id = upload("重新向量化.pdf", 2);
        KbDocument updated = dummyDoc(id, "重新向量化.pdf", 3);
        Mockito.when(documentMapper.selectById(id)).thenReturn(updated);

        documentService.reindex(id);

        KbDocPageQuery query = new KbDocPageQuery();
        query.setKbId(KB_ID);
        query.setStatus(3);
        assertEquals(1, documentService.page(query).getTotal());
    }

    @Test
    void shouldRebuildIndexFromDatabaseOnBootstrap() {
        KbDocument doc = dummyDoc(2001L, "历史文档.pdf", 2);
        Mockito.when(documentMapper.selectCount(Mockito.any())).thenReturn(1L);
        Mockito.doAnswer(inv -> {
            Page<KbDocument> page = inv.getArgument(0);
            page.setRecords(List.of(doc));
            page.setTotal(1);
            return page;
        }).when(documentMapper).selectPage(Mockito.any(Page.class), Mockito.any());

        provider.clear();
        assertEquals(0, support.countIndexed());

        bootstrap.bootstrap();

        KbDocPageQuery query = new KbDocPageQuery();
        query.setKbId(KB_ID);
        assertEquals(1, documentService.page(query).getTotal());

        // 幂等：重复回灌不堆积
        long after = support.countIndexed();
        bootstrap.bootstrap();
        assertEquals(after, support.countIndexed());
    }

    @Test
    void shouldFallbackToDatabaseWhenDisabled() {
        upload("回退文档.pdf", 2);
        properties.setEnabled(false);
        assertFalse(support.enabled());

        KbDocPageQuery query = new KbDocPageQuery();
        query.setKbId(KB_ID);
        assertNull(support.search(query));

        // DB 路径：Mapper 返回一页数据，服务照常封装
        KbDocument dbDoc = dummyDoc(3001L, "库里文档.pdf", 2);
        Mockito.doAnswer(inv -> {
            Page<KbDocument> page = inv.getArgument(0);
            page.setRecords(List.of(dbDoc));
            page.setTotal(1);
            return page;
        }).when(documentMapper).selectPage(Mockito.any(Page.class), Mockito.any());
        PageResult<KbDocumentVO> page = documentService.page(query);
        assertEquals(1, page.getTotal());
        assertEquals("库里文档.pdf", page.getList().get(0).getFileName());
    }

    // ==================== 辅助 ====================

    private PageResult<KbDocumentVO> query(java.util.function.Consumer<KbDocPageQuery> customizer) {
        KbDocPageQuery query = new KbDocPageQuery();
        customizer.accept(query);
        return documentService.page(query);
    }

    private Long upload(String fileName, Integer status) {
        KbDocUploadRequest request = new KbDocUploadRequest();
        request.setKbId(KB_ID);
        request.setFileName(fileName);
        request.setFileUrl("kb/" + fileName);
        request.setFileType("application/pdf");
        request.setFileSize(1024L);
        Long id = documentService.upload(request);
        // 上传后状态由管线推进，这里直接把最终状态写回索引（与 KbPipelineService 同源逻辑）
        KbDocument doc = dummyDoc(id, fileName, status);
        support.index(doc);
        return id;
    }

    private KbDocument newDoc(String fileName, Integer status) {
        KbDocument doc = dummyDoc(idSeq.incrementAndGet(), fileName, status);
        return doc;
    }

    private KbDocument dummyDoc(Long id, String fileName, Integer status) {
        KbDocument doc = new KbDocument();
        doc.setId(id);
        doc.setKbId(KB_ID);
        doc.setFileName(fileName);
        doc.setFileUrl("kb/" + fileName);
        doc.setFileType("application/pdf");
        doc.setStatus(status);
        doc.setChunkCount(0);
        doc.setVectorCount(0);
        doc.setCreateTime(LocalDateTime.now());
        return doc;
    }
}
