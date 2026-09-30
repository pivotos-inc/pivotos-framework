package com.pivotos.starter.search.template;

import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.api.enums.SearchProviderType;
import com.pivotos.starter.search.api.query.LambdaSearchQuery;
import com.pivotos.starter.search.api.template.SearchPage;
import com.pivotos.starter.search.fixture.OperLogDoc;
import com.pivotos.starter.search.route.SearchProviderFactory;
import com.pivotos.starter.search.simple.SimpleSearchProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门面端到端（走 simple 兜底）：验证「实体 → 文档 → 检索 → 实体」往返不失真。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class SearchTemplateImplTest {

    private SimpleSearchProvider provider;
    private SearchTemplateImpl template;

    @BeforeEach
    void setUp() {
        provider = new SimpleSearchProvider();
        SearchProperties properties = new SearchProperties();
        properties.setType("simple");
        template = new SearchTemplateImpl(new SearchProviderFactory(List.of(provider), properties), properties);

        List<OperLogDoc> docs = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            docs.add(new OperLogDoc((long) i, "操作" + i, i % 2, i % 2 == 0,
                    LocalDateTime.of(2026, 1, i, 0, 0)));
        }
        template.indexBatch(docs);
    }

    @Test
    void shouldReportEffectiveType() {
        assertEquals(SearchProviderType.SIMPLE, template.type());
    }

    @Test
    void shouldRoundTripEntityThroughIndexAndSearch() {
        SearchPage<OperLogDoc> page = template.search(LambdaSearchQuery.of(OperLogDoc.class).page(1, 10));

        assertEquals(5, page.getTotal());
        assertEquals(5, page.getRecords().size());
        OperLogDoc first = page.getRecords().get(0);
        assertEquals("操作1", first.getTitle());
        assertEquals(1L, first.getId());
        // LocalDateTime 经 JSON 往返后必须还原成时间类型而不是字符串
        assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), first.getOperTime());
    }

    @Test
    void shouldFilterWithLambdaConditions() {
        SearchPage<OperLogDoc> page = template.search(LambdaSearchQuery.of(OperLogDoc.class)
                .eq(OperLogDoc::getStatus, 1)
                .page(1, 10));
        // status = i % 2，i=1..5 → 1,0,1,0,1
        assertEquals(3, page.getTotal());
        assertTrue(page.getRecords().stream().allMatch(d -> d.getStatus() == 1));
    }

    @Test
    void shouldSupportLikeAndOrderAndPaging() {
        SearchPage<OperLogDoc> page = template.search(LambdaSearchQuery.of(OperLogDoc.class)
                .like(OperLogDoc::getTitle, "操作")
                .orderByDesc(OperLogDoc::getOperTime)
                .page(1, 2));
        assertEquals(5, page.getTotal());
        assertEquals(2, page.getRecords().size());
        assertEquals(3, page.getPages());
        assertEquals("操作5", page.getRecords().get(0).getTitle());
    }

    @Test
    void shouldCount() {
        assertEquals(5, template.count(LambdaSearchQuery.of(OperLogDoc.class)));
        assertEquals(2, template.count(LambdaSearchQuery.of(OperLogDoc.class).eq(OperLogDoc::getStatus, 0)));
        assertEquals(3, template.count(LambdaSearchQuery.of(OperLogDoc.class).eq(OperLogDoc::getStatus, 1)));
    }

    @Test
    void shouldOverwriteSameIdAndDelete() {
        template.index(new OperLogDoc(1L, "覆盖后", 9, true, LocalDateTime.now()));
        assertEquals(5, template.count(LambdaSearchQuery.of(OperLogDoc.class)));
        assertEquals(1, template.count(LambdaSearchQuery.of(OperLogDoc.class).eq(OperLogDoc::getTitle, "覆盖后")));

        template.delete("oper-log-doc", "1");
        assertEquals(4, template.count(LambdaSearchQuery.of(OperLogDoc.class)));
    }

    @Test
    void shouldApplyIndexPrefix() {
        SearchProperties properties = new SearchProperties();
        properties.setType("simple");
        properties.setIndexPrefix("dev");
        SearchTemplateImpl prefixed = new SearchTemplateImpl(
                new SearchProviderFactory(List.of(provider), properties), properties);
        prefixed.index(new OperLogDoc(100L, "带前缀", 1, true, LocalDateTime.now()));

        assertTrue(provider.existsIndex("dev_oper-log-doc"));
        assertEquals(1, prefixed.count(LambdaSearchQuery.of(OperLogDoc.class)));
    }

    @Test
    void shouldRejectNullQuery() {
        assertThrows(com.pivotos.starter.search.api.exception.SearchException.class,
                () -> template.search(null));
    }
}
