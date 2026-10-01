package com.pivotos.starter.datainspect.redis;

import com.pivotos.starter.datainspect.api.config.DataInspectProperties;
import com.pivotos.starter.datainspect.api.enums.Capability;
import com.pivotos.starter.datainspect.api.enums.DataSourceType;
import com.pivotos.starter.datainspect.api.model.ComponentSnapshot;
import com.pivotos.starter.datainspect.api.model.PreviewRequest;
import com.pivotos.starter.datainspect.api.model.QueryRequest;
import com.pivotos.starter.datainspect.api.model.QueryResult;
import com.pivotos.starter.datainspect.api.model.SchemaItem;
import com.pivotos.starter.datainspect.api.model.TableItem;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redis 数据监控（Mockito 替身，不连真机；真机验证走 E2E）。
 *
 * <p>重点覆盖三件事：① 三重保护（key 上限 / SCAN 轮次 / value 截断）真的生效；
 * ② 敏感 key 的 value 必须脱敏（只看列名会漏）；③ 没有 QUERY 能力。
 */
class RedisInspectorTest {

    private static DataInspectProperties props() {
        return new DataInspectProperties();
    }

    private static RedissonClient clientWith(RKeys keys) {
        RedissonClient client = Mockito.mock(RedissonClient.class);
        Mockito.when(client.getKeys()).thenReturn(keys);
        return client;
    }

    // ---------- 身份与降级 ----------

    @Test
    void 身份与能力集_无QUERY能力() {
        RedisInspector inspector = new RedisInspector(null, props());
        assertEquals(DataSourceType.REDIS, inspector.type());
        assertTrue(inspector.capabilities().contains(Capability.LIST_TABLES));
        assertTrue(inspector.capabilities().contains(Capability.PREVIEW));
        assertFalse(inspector.capabilities().contains(Capability.QUERY),
                "Redis 无等价的语句级闸门，只暴露结构化浏览");
    }

    @Test
    void 客户端未装配时降级且绝不抛异常() {
        RedisInspector inspector = new RedisInspector(null, props());
        assertFalse(inspector.isAvailable());
        ComponentSnapshot snapshot = inspector.snapshot();
        assertFalse(snapshot.isAvailable());
        assertEquals("UNREACHABLE", snapshot.getReasonCode());
        assertNotNull(snapshot.getReason());
        assertTrue(inspector.listSchemas().isEmpty());
        assertTrue(inspector.listTables("db0").isEmpty());
    }

    @Test
    void 连接不可达时降级为UNREACHABLE() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenThrow(new IllegalStateException("Redis connection refused"));
        RedisInspector inspector = new RedisInspector(clientWith(keys), props());
        assertFalse(inspector.isAvailable());
        assertTrue(inspector.snapshot().getReason().contains("refused"));
    }

    // ---------- 元数据 ----------

    @Test
    void 库清单返回当前库与key总数() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(24L);
        RedisInspector inspector = new RedisInspector(clientWith(keys), props(), 0);
        assertTrue(inspector.isAvailable());
        List<SchemaItem> schemas = inspector.listSchemas();
        assertEquals(1, schemas.size());
        assertEquals("db0", schemas.get(0).getName());
        assertEquals(24, schemas.get(0).getItemCount());
    }

    @Test
    void 库号跟随配置_database非0时不再是db0() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(3L);
        RedisInspector inspector = new RedisInspector(clientWith(keys), props(), 2);
        assertEquals("db2", inspector.listSchemas().get(0).getName());
    }

    @Test
    void key上限生效_超限即停() {
        DataInspectProperties properties = props();
        properties.getRedis().setMaxKeys(2);
        properties.getRedis().setScanCount(1);
        properties.getRedis().setMaxScanIterations(100);

        List<String> all = List.of("k1", "k2", "k3", "k4", "k5");
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(5L);
        Mockito.when(keys.getKeysByPattern("*", 1)).thenReturn(all);
        Mockito.when(keys.getType(Mockito.anyString())).thenReturn(RType.OBJECT);
        Mockito.when(keys.remainTimeToLive(Mockito.anyString())).thenReturn(-1L);

        RedisInspector inspector = new RedisInspector(clientWith(keys), properties);
        List<TableItem> items = inspector.listTables("db0", "*");
        assertEquals(2, items.size(), "key 上限是硬保护：超过 maxKeys 必须停止遍历");
        assertEquals("string", items.get(0).getType());
        assertEquals(-1, items.get(0).getTtl());
    }

    @Test
    void SCAN轮次上限生效() {
        DataInspectProperties properties = props();
        properties.getRedis().setMaxKeys(1000);
        properties.getRedis().setScanCount(2);
        properties.getRedis().setMaxScanIterations(3);   // 最多 3 轮 × 2 = 6 个 key

        List<String> all = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            all.add("k" + i);
        }
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(50L);
        Mockito.when(keys.getKeysByPattern("*", 2)).thenReturn(all);
        Mockito.when(keys.getType(Mockito.anyString())).thenReturn(RType.OBJECT);
        Mockito.when(keys.remainTimeToLive(Mockito.anyString())).thenReturn(-1L);

        RedisInspector inspector = new RedisInspector(clientWith(keys), properties);
        assertEquals(6, inspector.listTables("db0").size(),
                "轮次上限（3 轮 × 每轮 2 个）必须独立于 key 上限生效");
    }

    @Test
    void pattern被透传给SCAN_未传时用默认pattern() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(1L);
        Mockito.when(keys.getKeysByPattern(Mockito.anyString(), Mockito.anyInt())).thenReturn(List.of());

        RedisInspector inspector = new RedisInspector(clientWith(keys), props());
        inspector.listTables("db0", "sys:*");
        Mockito.verify(keys).getKeysByPattern("sys:*", 500);

        inspector.listTables("db0", null);
        Mockito.verify(keys).getKeysByPattern("*", 500);
    }

    @Test
    void 单个key元数据失败不影响整体清单() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(2L);
        Mockito.when(keys.getKeysByPattern("*", 500)).thenReturn(List.of("k1", "k2"));
        Mockito.when(keys.getType("k1")).thenThrow(new IllegalStateException("boom"));
        Mockito.when(keys.getType("k2")).thenReturn(RType.MAP);
        Mockito.when(keys.remainTimeToLive(Mockito.anyString())).thenReturn(60000L);

        RedisInspector inspector = new RedisInspector(clientWith(keys), props());
        List<TableItem> items = inspector.listTables("db0");
        assertEquals(2, items.size());
        assertEquals("unknown", items.get(0).getType());
        assertEquals("hash", items.get(1).getType());
        assertEquals(60, items.get(1).getTtl());
    }

    // ---------- 预览 ----------

    @Test
    void string预览_敏感key的value必须脱敏() {
        DataInspectProperties properties = props();
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(1L);
        Mockito.when(keys.getType("Authorization:sys-user:token:abc")).thenReturn(RType.OBJECT);
        Mockito.when(keys.remainTimeToLive("Authorization:sys-user:token:abc")).thenReturn(3600_000L);
        RedissonClient client = clientWith(keys);
        RBucket<Object> bucket = Mockito.mock(RBucket.class);
        Mockito.when(bucket.get()).thenReturn("abcdefgh");
        Mockito.when(client.getBucket("Authorization:sys-user:token:abc")).thenReturn(bucket);

        RedisInspector inspector = new RedisInspector(client, properties);
        QueryResult result = inspector.preview(PreviewRequest.builder()
                .component("redis").schema("db0").table("Authorization:sys-user:token:abc")
                .pageNum(1).pageSize(20).build());
        assertTrue(result.isAvailable());
        assertEquals(1, result.getRows().size());
        Map<String, Object> row = result.getRows().get(0);
        assertEquals("********", row.get("value"), "key 名含 token → value 必须脱敏（列名 value 本身不命中）");
        assertEquals(3600, ((Number) row.get("ttl")).longValue());
        assertEquals(8, ((Number) row.get("length")).intValue());
        assertTrue(result.getColumns().stream().anyMatch(c -> "value".equals(c.getName()) && c.isMasked()));
    }

    @Test
    void string预览_普通key不脱敏() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(1L);
        Mockito.when(keys.getType("sys:config:site")).thenReturn(RType.OBJECT);
        Mockito.when(keys.remainTimeToLive("sys:config:site")).thenReturn(-1L);
        RedissonClient client = clientWith(keys);
        RBucket<Object> bucket = Mockito.mock(RBucket.class);
        Mockito.when(bucket.get()).thenReturn("PivotOS");
        Mockito.when(client.getBucket("sys:config:site")).thenReturn(bucket);

        RedisInspector inspector = new RedisInspector(client, props());
        QueryResult result = inspector.preview(PreviewRequest.builder()
                .component("redis").schema("db0").table("sys:config:site").build());
        assertEquals("PivotOS", result.getRows().get(0).get("value"));
        assertEquals(-1, ((Number) result.getRows().get(0).get("ttl")).longValue());
    }

    @Test
    void value截断生效并给出warning() {
        DataInspectProperties properties = props();
        properties.getRedis().setValueTruncateBytes(5);
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(1L);
        Mockito.when(keys.getType("big")).thenReturn(RType.OBJECT);
        Mockito.when(keys.remainTimeToLive("big")).thenReturn(-1L);
        RedissonClient client = clientWith(keys);
        RBucket<Object> bucket = Mockito.mock(RBucket.class);
        Mockito.when(bucket.get()).thenReturn("0123456789");
        Mockito.when(client.getBucket("big")).thenReturn(bucket);

        RedisInspector inspector = new RedisInspector(client, properties);
        QueryResult result = inspector.preview(PreviewRequest.builder()
                .component("redis").schema("db0").table("big").build());
        assertTrue(String.valueOf(result.getRows().get(0).get("value")).endsWith("(truncated)"));
        assertTrue(result.isTruncated());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("截断")));
    }

    @Test
    void hash预览_按分页区间取值并脱敏() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(1L);
        Mockito.when(keys.getType("user:1")).thenReturn(RType.MAP);
        RedissonClient client = clientWith(keys);
        RMap<Object, Object> map = Mockito.mock(RMap.class);
        Map<Object, Object> entries = new LinkedHashMap<>();
        entries.put("username", "admin");
        entries.put("password", "12345678");
        Mockito.when(map.entrySet("*", 500)).thenReturn(entries.entrySet());
        Mockito.when(map.size()).thenReturn(2);
        Mockito.when(client.getMap("user:1")).thenReturn(map);

        RedisInspector inspector = new RedisInspector(client, props());
        QueryResult result = inspector.preview(PreviewRequest.builder()
                .component("redis").schema("db0").table("user:1").pageNum(1).pageSize(20).build());
        assertTrue(result.isAvailable());
        assertEquals(2, result.getRows().size());
        assertEquals("admin", result.getRows().get(0).get("value"));
        assertEquals("********", result.getRows().get(1).get("value"), "字段名命中 password 规则");
        assertEquals(2, result.getTotal());
    }

    @Test
    void list预览_按offset取区间() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(1L);
        Mockito.when(keys.getType("queue")).thenReturn(RType.LIST);
        RedissonClient client = clientWith(keys);
        RList<Object> list = Mockito.mock(RList.class);
        Mockito.when(list.range(2, 3)).thenReturn(List.of("c", "d"));
        Mockito.when(list.size()).thenReturn(4);
        Mockito.when(client.getList("queue")).thenReturn(list);

        RedisInspector inspector = new RedisInspector(client, props());
        QueryResult result = inspector.preview(PreviewRequest.builder()
                .component("redis").schema("db0").table("queue").pageNum(2).pageSize(2).build());
        assertEquals(2, result.getRows().size());
        assertEquals(2, ((Number) result.getRows().get(0).get("index")).intValue());
        assertTrue(result.getWarnings().stream().anyMatch(w -> w.contains("offset")));
    }

    @Test
    void 未指定key时预览被拒() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(1L);
        RedisInspector inspector = new RedisInspector(clientWith(keys), props());
        QueryResult result = inspector.preview(PreviewRequest.builder().component("redis").schema("db0").build());
        assertFalse(result.isAvailable());
        assertEquals("FORBIDDEN", result.getReasonCode());
    }

    @Test
    void 自由查询入口不开放() {
        RedisInspector inspector = new RedisInspector(null, props());
        QueryResult result = inspector.query(QueryRequest.builder()
                .component("redis").statement("FLUSHALL").build());
        assertFalse(result.isAvailable());
        assertEquals("UNSUPPORTED", result.getReasonCode());
    }

    // ---------- 统计 ----------

    @Test
    void 统计返回元素数与TTL() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(1L);
        Mockito.when(keys.getType("user:1")).thenReturn(RType.MAP);
        Mockito.when(keys.remainTimeToLive("user:1")).thenReturn(120_000L);
        RedissonClient client = clientWith(keys);
        RMap<Object, Object> map = Mockito.mock(RMap.class);
        Mockito.when(map.size()).thenReturn(7);
        Mockito.when(client.getMap("user:1")).thenReturn(map);

        RedisInspector inspector = new RedisInspector(client, props());
        var stats = inspector.stats("db0", "user:1");
        assertEquals("hash", stats.getEngine());
        assertEquals(7, stats.getRowCount());
        assertEquals(120, ((Number) stats.getExtra().get("ttlSeconds")).longValue());
    }

    @Test
    void 统计在组件不可用时返回未知值() {
        RedisInspector inspector = new RedisInspector(null, props());
        var stats = inspector.stats("db0", "user:1");
        assertEquals(-1, stats.getRowCount());
        assertEquals(-1, stats.getSizeBytes());
    }

    @Test
    void 能力集不含QUERY_快照detail给出保护口径() {
        RKeys keys = Mockito.mock(RKeys.class);
        Mockito.when(keys.count()).thenReturn(0L);
        RedisInspector inspector = new RedisInspector(clientWith(keys), props());
        ComponentSnapshot snapshot = inspector.snapshot();
        assertTrue(snapshot.isAvailable());
        Set<Capability> capabilities = snapshot.getCapabilities();
        assertFalse(capabilities.contains(Capability.QUERY));
        assertTrue(snapshot.getDetail().contains("SCAN 保护"), "用户必须能看到三重保护口径");
    }
}
