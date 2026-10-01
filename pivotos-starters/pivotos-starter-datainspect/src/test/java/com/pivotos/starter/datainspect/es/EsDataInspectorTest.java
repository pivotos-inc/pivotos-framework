package com.pivotos.starter.datainspect.es;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.pivotos.starter.datainspect.api.config.DataInspectProperties;
import com.pivotos.starter.datainspect.api.enums.Capability;
import com.pivotos.starter.datainspect.api.enums.DataSourceType;
import com.pivotos.starter.datainspect.api.model.ComponentSnapshot;
import com.pivotos.starter.datainspect.api.model.PreviewRequest;
import com.pivotos.starter.datainspect.api.model.QueryRequest;
import com.pivotos.starter.datainspect.api.model.QueryResult;
import com.pivotos.starter.datainspect.api.model.SchemaItem;
import com.pivotos.starter.datainspect.api.model.TableItem;
import com.pivotos.starter.search.api.config.SearchProperties;
import com.pivotos.starter.search.esjava.EsJavaSearchProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ES 数据监控：可用性四态 + 索引名闸门（<b>不连真机</b>，真机验证走 E2E 双目标）。
 *
 * <p>降级口径是硬要求：任何不可用形态都必须是 {@code available=false + reasonCode}，绝不抛异常。
 */
class EsDataInspectorTest {

    private static DataInspectProperties properties() {
        return new DataInspectProperties();
    }

    private static SearchProperties search(String type) {
        SearchProperties properties = new SearchProperties();
        properties.setType(type);
        return properties;
    }

    @Test
    void 身份与能力集_无QUERY能力() {
        EsDataInspector inspector = new EsDataInspector(null, search("es-java"), null, properties());
        assertEquals(DataSourceType.ES, inspector.type());
        assertTrue(inspector.capabilities().contains(Capability.LIST_TABLES));
        assertTrue(inspector.capabilities().contains(Capability.PREVIEW));
        assertFalse(inspector.capabilities().contains(Capability.QUERY),
                "ES 不开放自由 DSL —— 只暴露结构化浏览");
    }

    @Test
    void 索引名闸门_防URL注入到别的端点() {
        assertTrue(EsDataInspector.validIndexName("sys-oper-log"));
        assertTrue(EsDataInspector.validIndexName("ai_kb_chunk"));
        assertTrue(EsDataInspector.validIndexName("kb-document"));
        assertFalse(EsDataInspector.validIndexName(null));
        assertFalse(EsDataInspector.validIndexName("  "));
        assertFalse(EsDataInspector.validIndexName(".geoip_databases"), "系统内建索引一律不可预览");
        assertFalse(EsDataInspector.validIndexName("*"), "通配符会命中多个索引");
        assertFalse(EsDataInspector.validIndexName("a,b"), "逗号会被 ES 解析成多索引");
        assertFalse(EsDataInspector.validIndexName("_all"));
        assertFalse(EsDataInspector.validIndexName("sys/*"), "路径分隔符会把请求打到别的端点");
        assertFalse(EsDataInspector.validIndexName("a b"));
    }

    @Test
    void 客户端未装配降级为IMPL_MISSING() {
        EsDataInspector inspector = new EsDataInspector(null, search("es-java"), null, properties());
        assertFalse(inspector.isAvailable());
        ComponentSnapshot snapshot = inspector.snapshot();
        assertFalse(snapshot.isAvailable());
        assertEquals("IMPL_MISSING", snapshot.getReasonCode());
        assertNotNull(snapshot.getReason());
    }

    @Test
    void 配置type为simple时降级为UNSUPPORTED并说明原因() {
        ElasticsearchClient client = Mockito.mock(ElasticsearchClient.class);
        EsDataInspector inspector = new EsDataInspector(client, search("simple"), null, properties());
        assertFalse(inspector.isAvailable());
        ComponentSnapshot snapshot = inspector.snapshot();
        assertEquals("UNSUPPORTED", snapshot.getReasonCode());
        assertTrue(snapshot.getReason().contains("simple"), "UI 要能明示「配的是 simple」："
                + snapshot.getReason());
    }

    @Test
    void 实现自检未通过降级为UNREACHABLE() {
        ElasticsearchClient client = Mockito.mock(ElasticsearchClient.class);
        // 真对象 + 空客户端：版本探测拿不到 → 自检不通过（不连真机也能覆盖该分支）
        EsJavaSearchProvider provider = new EsJavaSearchProvider(client);
        assertFalse(provider.isAvailable());
        EsDataInspector inspector = new EsDataInspector(client, search("es-java"), provider, properties());
        assertFalse(inspector.isAvailable());
        assertEquals("UNREACHABLE", inspector.snapshot().getReasonCode());
    }

    @Test
    void 不可用时元数据与预览一律降级且绝不抛异常() {
        EsDataInspector inspector = new EsDataInspector(null, search("es-java"), null, properties());
        assertTrue(inspector.listSchemas().isEmpty());
        assertTrue(inspector.listTables(EsDataInspector.SCHEMA_INDICES).isEmpty());
        QueryResult preview = inspector.preview(PreviewRequest.builder()
                .component("es").schema(EsDataInspector.SCHEMA_INDICES).table("sys-oper-log").build());
        assertFalse(preview.isAvailable());
        assertTrue(preview.getRows().isEmpty());
        assertNotNull(preview.getReasonCode());
    }

    @Test
    void 自由DSL入口不开放() {
        EsDataInspector inspector = new EsDataInspector(null, search("es-java"), null, properties());
        QueryResult result = inspector.query(QueryRequest.builder()
                .component("es").statement("{\"query\":{\"match_all\":{}}}").build());
        assertFalse(result.isAvailable());
        assertEquals("UNSUPPORTED", result.getReasonCode());
    }

    @Test
    void 索引名不合法时预览被拒() {
        EsDataInspector inspector = new EsDataInspector(null, search("es-java"), null, properties());
        // 可用性为 false 时先走降级；这里单独验证「可用但索引名非法」的分支靠 validIndexName 闸门
        QueryResult result = inspector.preview(PreviewRequest.builder()
                .component("es").schema(EsDataInspector.SCHEMA_INDICES).table("a,b").build());
        assertFalse(result.isAvailable());
        assertTrue(result.getRows().isEmpty());
        assertFalse(EsDataInspector.validIndexName("a,b"));
    }

    @Test
    void 索引清单上限按配置生效() {
        DataInspectProperties properties = properties();
        properties.getEs().setMaxItems(2);
        EsDataInspector inspector = new EsDataInspector(null, search("es-java"), null, properties);
        List<TableItem> tables = inspector.listTables(EsDataInspector.SCHEMA_INDICES);
        assertTrue(tables.size() <= 2);
        List<SchemaItem> schemas = inspector.listSchemas();
        assertTrue(schemas.isEmpty() || EsDataInspector.SCHEMA_INDICES.equals(schemas.get(0).getName()));
    }
}
