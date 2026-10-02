package com.pivotos.starter.datainspect.api.route;

import com.pivotos.starter.datainspect.api.enums.Capability;
import com.pivotos.starter.datainspect.api.enums.DataSourceType;
import com.pivotos.starter.datainspect.api.enums.RejectReason;
import com.pivotos.starter.datainspect.api.model.ComponentSnapshot;
import com.pivotos.starter.datainspect.api.model.PreviewRequest;
import com.pivotos.starter.datainspect.api.model.QueryRequest;
import com.pivotos.starter.datainspect.api.model.QueryResult;
import com.pivotos.starter.datainspect.api.model.SchemaItem;
import com.pivotos.starter.datainspect.api.model.StatsItem;
import com.pivotos.starter.datainspect.api.model.TableItem;
import com.pivotos.starter.datainspect.api.spi.DataSourceInspector;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组件路由工厂单测（照抄 SearchProviderFactoryTest 的四条路径：命中 / 缺席降级 / 垃圾 type / 空实现）。
 *
 * <p>与检索工厂的语义差异：这里<b>不做实现回落</b>，缺席的组件以 IMPL_MISSING 出现在清单里。
 */
class DataInspectorFactoryTest {

    /** 测试替身：构造即可用，不连任何中间件 */
    private static class StubInspector implements DataSourceInspector {

        private final DataSourceType type;
        private final boolean available;

        StubInspector(DataSourceType type, boolean available) {
            this.type = type;
            this.available = available;
        }

        @Override
        public DataSourceType type() {
            return type;
        }

        @Override
        public Set<Capability> capabilities() {
            return Set.of(Capability.LIST_SCHEMAS, Capability.PREVIEW);
        }

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public ComponentSnapshot snapshot() {
            return ComponentSnapshot.of(type, available,
                    available ? null : RejectReason.UNREACHABLE, "stub", capabilities());
        }

        @Override
        public List<SchemaItem> listSchemas() {
            return List.of();
        }

        @Override
        public List<TableItem> listTables(String schema) {
            return List.of();
        }

        @Override
        public QueryResult preview(PreviewRequest request) {
            return QueryResult.builder().available(available).build();
        }

        @Override
        public QueryResult query(QueryRequest request) {
            return QueryResult.builder().available(available).build();
        }

        @Override
        public StatsItem stats(String schema, String table) {
            return StatsItem.builder().build();
        }
    }

    /** 采集时抛异常的实现：工厂必须自己降级，不能让整个接口 500 */
    private static class BoomInspector extends StubInspector {

        BoomInspector() {
            super(DataSourceType.REDIS, true);
        }

        @Override
        public ComponentSnapshot snapshot() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void 命中已注册组件() {
        DataInspectorFactory factory = new DataInspectorFactory(List.of(new StubInspector(DataSourceType.MYSQL, true)));
        Optional<DataSourceInspector> inspector = factory.get("mysql");
        assertTrue(inspector.isPresent());
        assertEquals(DataSourceType.MYSQL, inspector.get().type());
        assertEquals(List.of(DataSourceType.MYSQL), factory.registeredTypes());
    }

    @Test
    void 缺席组件在清单里降级为IMPL_MISSING() {
        DataInspectorFactory factory = new DataInspectorFactory(List.of(new StubInspector(DataSourceType.MYSQL, true)));
        assertTrue(factory.get("redis").isEmpty());
        ComponentSnapshot redis = factory.components().stream()
                .filter(c -> c.getType().equals("redis")).findFirst().orElseThrow();
        assertFalse(redis.isAvailable());
        assertEquals(RejectReason.IMPL_MISSING.name(), redis.getReasonCode());
        assertTrue(redis.getReason().contains("redis"));
    }

    @Test
    void 垃圾type不报错且返回空() {
        DataInspectorFactory factory = new DataInspectorFactory(List.of(new StubInspector(DataSourceType.MYSQL, true)));
        assertTrue(factory.get("not-exist").isEmpty());
        assertTrue(factory.get((DataSourceType) null).isEmpty());
    }

    @Test
    void 空实现列表时全部组件占位且清单不为空() {
        DataInspectorFactory factory = new DataInspectorFactory(List.of());
        assertTrue(factory.registeredTypes().isEmpty());
        assertEquals(DataSourceType.values().length, factory.components().size());
        assertTrue(factory.components().stream().noneMatch(ComponentSnapshot::isAvailable));
    }

    @Test
    void 单个组件采集异常不影响其它组件() {
        DataInspectorFactory factory = new DataInspectorFactory(
                List.of(new StubInspector(DataSourceType.MYSQL, true), new BoomInspector()));
        List<ComponentSnapshot> snapshots = factory.components();
        ComponentSnapshot mysql = snapshots.stream().filter(c -> c.getType().equals("mysql")).findFirst().orElseThrow();
        ComponentSnapshot redis = snapshots.stream().filter(c -> c.getType().equals("redis")).findFirst().orElseThrow();
        assertTrue(mysql.isAvailable());
        assertFalse(redis.isAvailable());
        assertEquals(RejectReason.COLLECT_FAILED.name(), redis.getReasonCode());
    }

    @Test
    void 重复type后注册者覆盖前者且清单不重复() {
        DataInspectorFactory factory = new DataInspectorFactory(List.of(
                new StubInspector(DataSourceType.MYSQL, true),
                new StubInspector(DataSourceType.MYSQL, false)));
        assertEquals(1, factory.registeredTypes().size());
        assertFalse(factory.get("mysql").orElseThrow().isAvailable());
    }

    @Test
    void 未注册类型为扩展点占位可见() {
        DataInspectorFactory factory = new DataInspectorFactory(List.of(new StubInspector(DataSourceType.MYSQL, true)));
        assertTrue(factory.unregisteredTypes().contains(DataSourceType.NEO4J));
        assertTrue(factory.unregisteredTypes().contains(DataSourceType.CLICKHOUSE));
        assertFalse(factory.unregisteredTypes().contains(DataSourceType.MYSQL));
    }

    @Test
    void 类型解析大小写与空格不敏感() {
        assertEquals(DataSourceType.MYSQL, DataSourceType.of(" MySQL "));
        assertEquals(DataSourceType.ES, DataSourceType.of("ES"));
        assertEquals(null, DataSourceType.of(""));
        assertTrue(new DataInspectorFactory(null).get("mysql").isEmpty(), "无实现时不应抛异常");
    }
}
