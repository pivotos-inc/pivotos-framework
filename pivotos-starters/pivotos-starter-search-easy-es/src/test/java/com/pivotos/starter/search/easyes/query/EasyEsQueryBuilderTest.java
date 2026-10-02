package com.pivotos.starter.search.easyes.query;

import co.elastic.clients.elasticsearch.core.SearchRequest;
import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.enums.SearchOp;
import com.pivotos.starter.search.api.query.SearchCriteria;
import com.pivotos.starter.search.api.query.SearchOrder;
import com.pivotos.starter.search.easyes.client.EasyEsMapperFactory;
import org.dromara.easyes.core.conditions.select.LambdaEsQueryWrapper;
import org.dromara.easyes.core.kernel.BaseEsMapperImpl;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Easy-ES 条件构建：验证「条件树 → LambdaEsQueryWrapper → Easy-ES 编译出的 SearchRequest」。
 * <p>这一步是纯构建（不发网络请求），因此是本机无 ES 时唯一能真正验证 Easy-ES 链路的地方；
 * 真实执行需在有 ES 的环境补验（已写入收口报告遗留项）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
class EasyEsQueryBuilderTest {

    private final BaseEsMapperImpl<HashMap> mapper = EasyEsMapperFactory.createMapper(null);

    private String compile(List<SearchCriteria> criteria, List<SearchOrder> orders) {
        LambdaEsQueryWrapper<HashMap> wrapper = EasyEsQueryBuilder.build(criteria, orders);
        SearchRequest request = mapper.getSearchBuilder(wrapper)
                .index("test-index")
                .from(0)
                .size(10)
                .build();
        return request.toString();
    }

    @Test
    void shouldCompileEmptyCriteria() {
        assertNotNull(compile(List.of(), List.of()));
    }

    @Test
    void shouldCompileTermCondition() {
        String json = compile(List.of(SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1)), List.of());
        assertTrue(json.contains("\"term\""), json);
        assertTrue(json.contains("\"status\""), json);
    }

    @Test
    void shouldCompileLikeCondition() {
        String json = compile(List.of(SearchCriteria.of("title", SearchOp.LIKE, SearchLogic.AND, "登录")), List.of());
        assertTrue(json.contains("\"title\""), json);
    }

    @Test
    void shouldCompileAllOperatorsWithoutError() {
        for (SearchOp op : SearchOp.values()) {
            List<SearchCriteria> criteria = switch (op) {
                case IS_NULL, IS_NOT_NULL -> List.of(SearchCriteria.of("title", op, SearchLogic.AND));
                case BETWEEN -> List.of(SearchCriteria.of("age", op, SearchLogic.AND, 18, 60));
                case IN, NOT_IN -> List.of(SearchCriteria.of("status", op, SearchLogic.AND, List.of(1, 2)));
                default -> List.of(SearchCriteria.of("title", op, SearchLogic.AND, "x"));
            };
            assertNotNull(compile(criteria, List.of()), "操作符 " + op + " 编译失败");
        }
    }

    @Test
    void shouldCompileSortConditions() {
        String json = compile(List.of(), List.of(SearchOrder.desc("operTime"), SearchOrder.asc("id")));
        assertTrue(json.contains("operTime"), json);
        assertTrue(json.contains("\"sort\""), json);
        assertTrue(json.contains("\"desc\""), json);
    }

    @Test
    void shouldSetIndexNameOnCompiledRequest() {
        String json = compile(List.of(), List.of());
        assertTrue(json.contains("test-index"), json);
    }

    @Test
    void shouldApplyPaging() {
        String json = compile(List.of(), List.of());
        assertTrue(json.contains("\"from\":0"), json);
        assertTrue(json.contains("\"size\":10"), json);
    }

    @Test
    void shouldCompileOrLogic() {
        String json = compile(List.of(
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.AND, 1),
                SearchCriteria.of("status", SearchOp.EQ, SearchLogic.OR, 2)), List.of());
        assertNotNull(json);
        assertTrue(json.contains("\"bool\""), json);
    }

    @Test
    void shouldCompileBetweenAsRange() {
        String json = compile(List.of(SearchCriteria.of("age", SearchOp.BETWEEN, SearchLogic.AND, 18, 60)), List.of());
        assertTrue(json.contains("\"range\""), json);
    }
}
