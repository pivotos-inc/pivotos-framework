package com.pivotos.starter.datainspect.redis;

import com.pivotos.starter.datainspect.api.config.DataInspectProperties;
import com.pivotos.starter.datainspect.api.enums.Capability;
import com.pivotos.starter.datainspect.api.enums.DataSourceType;
import com.pivotos.starter.datainspect.api.enums.RejectReason;
import com.pivotos.starter.datainspect.api.model.ColumnItem;
import com.pivotos.starter.datainspect.api.model.ComponentSnapshot;
import com.pivotos.starter.datainspect.api.model.PreviewRequest;
import com.pivotos.starter.datainspect.api.model.QueryRequest;
import com.pivotos.starter.datainspect.api.model.QueryResult;
import com.pivotos.starter.datainspect.api.model.SchemaItem;
import com.pivotos.starter.datainspect.api.model.StatsItem;
import com.pivotos.starter.datainspect.api.model.TableItem;
import com.pivotos.starter.datainspect.api.spi.DataSourceInspector;
import com.pivotos.starter.datainspect.security.ColumnMasker;
import com.pivotos.starter.datainspect.support.InspectValues;
import org.redisson.api.RList;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.ScoredEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Redis 数据监控实现（<b>纯只读</b>）：SCAN 列举 key + 按 TYPE 感知取值 + TTL + 统计。
 *
 * <p><b>三重保护（设计 §11-6 明列风险：SCAN 在超大库的性能）</b>
 * <ol>
 *   <li><b>SCAN 轮次上限</b>：{@code maxScanIterations}（默认 20 轮 × {@code scanCount} 个 key），
 *       防在大库上无限游走；</li>
 *   <li><b>key 上限</b>：{@code maxKeys}（默认 1000），防结果集爆炸；</li>
 *   <li><b>value 截断</b>：{@code valueTruncateBytes}（默认 2048 字符），防大 value 拖垮渲染与网络。</li>
 * </ol>
 * 命中任一上限都打 WARN 并停止遍历；<b>要查看更多必须用 pattern 收窄</b>（前端 Redis 面板提供 pattern 输入）。
 *
 * <p><b>为什么没有 QUERY 能力</b>：Redis 没有等价的「语句级闸门」——命令白名单无法像 SQL AST 那样静态判定
 * 副作用边界（一个 {@code EVAL} 就能绕过一切白名单）。按设计 §5 的取舍口径，
 * <b>宁可不开放，也不开放一个拦不住的入口</b>：本组件只提供结构化浏览。
 *
 * <p>命令面（实现层根本不提供写命令入口）：
 * {@code SCAN / TYPE / PTTL / GET / LRANGE / HSCAN / SSCAN / ZRANGE / STRLEN / HLEN / LLEN / SCARD / ZCARD / MEMORY USAGE}，
 * <b>禁 KEYS</b>（一律走 SCAN）。
 *
 * <p>租户语义（显式声明）：Redis 是共享缓存，<b>key 空间天然跨租户</b>，不做租户改写；
 * 靠 {@code monitor:data:list/preview} 权限 + 敏感 key 脱敏（列名<b>或 key 名</b>命中即脱敏）收口。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public class RedisInspector implements DataSourceInspector {

    private static final Logger log = LoggerFactory.getLogger(RedisInspector.class);

    /** 无 QUERY：不开放命令/语句执行入口 */
    private static final Set<Capability> CAPABILITIES = Set.of(
            Capability.LIST_SCHEMAS, Capability.LIST_TABLES, Capability.PREVIEW, Capability.STATS);

    /** RType 的 OBJECT 在 Redis 语义下即 string */
    private static final String STRING = "string";

    private final RedissonClient client;
    private final DataInspectProperties properties;
    private final ColumnMasker masker;

    /**
     * 当前库号。
     *
     * <p>为什么不从 Redisson 配置里读：{@code Config#getSingleServerConfig()} 是 <b>protected</b>，
     * 拿不到；而 Redisson 又是单库接入（不提供 SELECT 切库），因此库号由装配层从
     * {@code spring.data.redis.database} 传入（与 redisson-spring-boot-starter 同一个配置源）。
     */
    private final int database;

    /** 首次探测结论缓存：运行期不反复探测（与 ES 监控同口径，恢复需重启） */
    private volatile Boolean available;
    private volatile String unavailableDetail;

    public RedisInspector(RedissonClient client, DataInspectProperties properties) {
        this(client, properties, 0);
    }

    public RedisInspector(RedissonClient client, DataInspectProperties properties, int database) {
        this.client = client;
        this.properties = properties == null ? new DataInspectProperties() : properties;
        this.masker = new ColumnMasker(this.properties.getMaskColumnRegex());
        this.database = Math.max(0, database);
    }

    // ---------- SPI 基础 ----------

    @Override
    public DataSourceType type() {
        return DataSourceType.REDIS;
    }

    @Override
    public Set<Capability> capabilities() {
        return CAPABILITIES;
    }

    @Override
    public boolean isAvailable() {
        Boolean cached = available;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (available == null) {
                probe();
            }
            return Boolean.TRUE.equals(available);
        }
    }

    @Override
    public ComponentSnapshot snapshot() {
        if (!isAvailable()) {
            return ComponentSnapshot.unavailable(DataSourceType.REDIS, RejectReason.UNREACHABLE, unavailableDetail);
        }
        return ComponentSnapshot.of(DataSourceType.REDIS, true, null, protectionText(), CAPABILITIES);
    }

    private void probe() {
        if (client == null) {
            available = false;
            unavailableDetail = "未装配 RedissonClient（未引入 pivotos-starter-redis 或未配置 Redis）";
            return;
        }
        try {
            client.getKeys().count();
            available = true;
            unavailableDetail = null;
        } catch (Exception e) {
            available = false;
            unavailableDetail = rootMessage(e);
            log.warn("[PivotOS][datainspect] Redis 组件不可用，全部接口走降级：{}", unavailableDetail);
        }
    }

    /** 三重保护的运行期口径，直接给前端展示（用户才知道「为什么只看到一部分」） */
    private String protectionText() {
        DataInspectProperties.Redis redis = properties.getRedis();
        return "SCAN 保护：单页最多 " + redis.getMaxKeys() + " 个 key / 最多 " + redis.getMaxScanIterations()
                + " 轮（每轮 " + redis.getScanCount() + "）/ value 截断 " + redis.getValueTruncateBytes() + " 字符";
    }

    private String currentSchema() {
        return "db" + database;
    }

    // ---------- 元数据 ----------

    @Override
    public List<SchemaItem> listSchemas() {
        if (!isAvailable()) {
            return List.of();
        }
        long count;
        try {
            count = client.getKeys().count();
        } catch (Exception e) {
            log.warn("[PivotOS][datainspect] Redis key 总数采集失败：{}", rootMessage(e));
            count = -1;
        }
        String db = currentSchema();
        return List.of(SchemaItem.builder().name(db).label(db + "（当前库）").itemCount(count).build());
    }

    @Override
    public List<TableItem> listTables(String schema) {
        return listTables(schema, null);
    }

    @Override
    public List<TableItem> listTables(String schema, String pattern) {
        if (!isAvailable()) {
            return List.of();
        }
        DataInspectProperties.Redis redis = properties.getRedis();
        String effectivePattern = (pattern == null || pattern.isBlank()) ? defaultPattern() : pattern.trim();
        int maxKeys = Math.max(1, redis.getMaxKeys());
        int maxRounds = Math.max(1, redis.getMaxScanIterations());
        int scanCount = Math.max(1, redis.getScanCount());
        // ① 轮次上限换算成「最多遍历的 key 个数」：一轮 = scanCount 个 key
        int maxExamined = maxRounds * scanCount;

        List<TableItem> items = new ArrayList<>();
        int examined = 0;
        try {
            Iterable<String> keys = client.getKeys().getKeysByPattern(effectivePattern, scanCount);
            for (String key : keys) {
                if (examined >= maxExamined) {
                    log.warn("[PivotOS][datainspect] Redis SCAN 触及轮次上限 {} 轮（pattern={}），已停止遍历——请用 pattern 收窄",
                            maxRounds, effectivePattern);
                    break;
                }
                if (items.size() >= maxKeys) {
                    log.warn("[PivotOS][datainspect] Redis SCAN 触及 key 上限 {}（pattern={}），已停止遍历——请用 pattern 收窄",
                            maxKeys, effectivePattern);
                    break;
                }
                examined++;
                items.add(toTableItem(key));
            }
        } catch (Exception e) {
            log.warn("[PivotOS][datainspect] Redis SCAN 失败（pattern={}）：{}", effectivePattern, rootMessage(e));
        }
        return items;
    }

    private TableItem toTableItem(String key) {
        TableItem.TableItemBuilder builder = TableItem.builder().name(key).schema(currentSchema()).rowCount(-1);
        try {
            builder.type(typeName(client.getKeys().getType(key)));
            builder.ttl(ttlSeconds(key));
        } catch (Exception e) {
            // 单个 key 的元数据失败不影响整体清单（降级为 unknown，绝不让整棵树消失）
            log.debug("[PivotOS][datainspect] Redis key 元数据采集失败：{}", rootMessage(e));
            builder.type("unknown").ttl(-1);
        }
        return builder.build();
    }

    @Override
    public StatsItem stats(String schema, String table) {
        String key = table;
        StatsItem.StatsItemBuilder builder = StatsItem.builder().schema(schema).table(key);
        if (!isAvailable() || key == null || key.isBlank()) {
            return builder.rowCount(-1).sizeBytes(-1).build();
        }
        try {
            RType type = client.getKeys().getType(key);
            builder.engine(typeName(type));
            builder.rowCount(elementCount(key, type));
            Map<String, Object> extra = new LinkedHashMap<>();
            extra.put("ttlSeconds", ttlSeconds(key));
            builder.extra(extra);
        } catch (Exception e) {
            log.warn("[PivotOS][datainspect] Redis 统计失败（key={}）：{}", key, rootMessage(e));
            return builder.rowCount(-1).sizeBytes(-1).build();
        }
        builder.sizeBytes(memoryUsage(key));
        return builder.build();
    }

    // ---------- 数据 ----------

    @Override
    public QueryResult preview(PreviewRequest request) {
        long start = System.currentTimeMillis();
        if (!isAvailable()) {
            return QueryResult.unavailable(RejectReason.UNREACHABLE, unavailableDetail);
        }
        String key = request == null ? null : request.getTable();
        if (key == null || key.isBlank()) {
            return QueryResult.unavailable(RejectReason.FORBIDDEN, "未指定 key");
        }
        int pageSize = request.getPageSize() <= 0 ? 20 : request.getPageSize();
        int pageNum = request.getPageNum() <= 0 ? 1 : request.getPageNum();
        int offset = (pageNum - 1) * pageSize;
        List<String> warnings = new ArrayList<>();

        RType type;
        try {
            type = client.getKeys().getType(key);
        } catch (Exception e) {
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, rootMessage(e));
        }
        if (type == null) {
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, "key 不存在或已过期：" + key);
        }

        QueryResult result;
        try {
            result = switch (type) {
                case MAP -> previewMap(key, offset, pageSize, warnings);
                case LIST -> previewList(key, offset, pageSize, warnings);
                case SET -> previewSet(key, pageSize, warnings);
                case ZSET -> previewZSet(key, offset, pageSize, warnings);
                case STREAM -> QueryResult.unavailable(RejectReason.UNSUPPORTED, "STREAM 类型暂不支持预览");
                default -> previewString(key, type);
            };
        } catch (Exception e) {
            log.warn("[PivotOS][datainspect] Redis 预览失败（key={}）：{}", key, rootMessage(e));
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, rootMessage(e));
        }
        result.setDurationMs(System.currentTimeMillis() - start);
        if (result.getWarnings() == null) {
            result.setWarnings(warnings);
        } else if (result.getWarnings() != warnings) {
            // 各 previewXxx 拿到的就是外层 warnings，这里避免自加导致重复
            result.getWarnings().addAll(warnings);
        }
        if (result.getTotal() <= 0 && result.getRows() != null) {
            result.setTotal(result.getRows().size());
        }
        return result;
    }

    @Override
    public QueryResult query(QueryRequest request) {
        // 无 QUERY 能力：即便被绕过前端直接调用，也只给结构化浏览，绝不提供命令执行入口
        return QueryResult.unavailable(RejectReason.UNSUPPORTED,
                "Redis 组件只提供结构化浏览（SCAN + TYPE 感知取值），不开放命令/语句执行入口");
    }

    // ---------- 各类型的取值实现（只读命令） ----------

    /** string：key / type / ttl / length / value 一行 */
    private QueryResult previewString(String key, RType type) {
        Object value;
        try {
            value = client.getBucket(key).get();
        } catch (Exception e) {
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, rootMessage(e));
        }
        boolean masked = masker.isSensitive("value", key);
        Object shown = normalize(masker.mask("value", value, key));
        List<ColumnItem> columns = List.of(
                column("key", false),
                column("type", false),
                column("ttl", false),
                column("length", false),
                column("value", masked));
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("key", key);
        row.put("type", typeName(type));
        row.put("ttl", ttlSeconds(key));
        row.put("length", value == null ? 0 : String.valueOf(value).length());
        row.put("value", shown);

        List<String> warnings = new ArrayList<>();
        boolean truncated = InspectValues.isTruncated(shown);
        if (masked) {
            warnings.add("已脱敏 1 个单元格（key 命中敏感名规则）");
        }
        if (truncated) {
            warnings.add("value 已截断至 " + properties.getRedis().getValueTruncateBytes() + " 字符");
        }
        return QueryResult.builder()
                .columns(columns)
                .rows(List.of(row))
                .total(1)
                .truncated(truncated)
                .warnings(warnings)
                .available(true)
                .build();
    }

    /** hash：HSCAN（带 count 提示），columns = index / field / value */
    private QueryResult previewMap(String key, int offset, int pageSize, List<String> warnings) {
        int limit = Math.min(pageSize, maxElements());
        RMap<Object, Object> map = client.getMap(key);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean maskedAny = false;
        boolean truncated = false;
        int index = 0;
        int skipped = 0;
        for (Map.Entry<Object, Object> entry : map.entrySet("*", scanCount())) {
            if (skipped < offset) {
                skipped++;
                continue;
            }
            if (rows.size() >= limit) {
                truncated = true;
                break;
            }
            String field = String.valueOf(entry.getKey());
            boolean masked = masker.isSensitive(field, key);
            maskedAny = maskedAny || masked;
            Object value = normalize(masker.mask(field, entry.getValue(), key));
            truncated = truncated || InspectValues.isTruncated(value);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index++);
            row.put("field", field);
            row.put("value", value);
            rows.add(row);
        }
        if (truncated) {
            warnings.add("集合元素已按单页上限 " + limit + " 截断（Redis 集合预览不做全量拉取）");
        }
        return collectionResult(key, rows, List.of("index", "field", "value"), maskedAny,
                countOf(key, RType.MAP), truncated, offset > 0, warnings);
    }

    /** list：LRANGE（offset ~ offset+limit-1） */
    private QueryResult previewList(String key, int offset, int pageSize, List<String> warnings) {
        int limit = Math.min(pageSize, maxElements());
        RList<Object> list = client.getList(key);
        List<Object> slice;
        try {
            slice = list.range(offset, offset + limit - 1);
        } catch (Exception e) {
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, rootMessage(e));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean maskedAny = false;
        boolean truncated = slice.size() >= limit;
        for (int i = 0; i < slice.size(); i++) {
            boolean masked = masker.isSensitive("value", key);
            maskedAny = maskedAny || masked;
            Object value = normalize(masker.mask("value", slice.get(i), key));
            truncated = truncated || InspectValues.isTruncated(value);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", offset + i);
            row.put("value", value);
            rows.add(row);
        }
        if (truncated) {
            warnings.add("集合元素已按单页上限 " + limit + " 截断（Redis 集合预览不做全量拉取）");
        }
        return collectionResult(key, rows, List.of("index", "value"), maskedAny,
                countOf(key, RType.LIST), truncated, offset > 0, warnings);
    }

    /** set：SSCAN（带 count 提示） */
    private QueryResult previewSet(String key, int pageSize, List<String> warnings) {
        int limit = Math.min(pageSize, maxElements());
        RSet<Object> set = client.getSet(key);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean maskedAny = false;
        boolean truncated = false;
        Iterator<Object> iterator = set.iterator("*", scanCount());
        int index = 0;
        while (iterator.hasNext()) {
            if (rows.size() >= limit) {
                truncated = true;
                break;
            }
            String member = String.valueOf(iterator.next());
            boolean masked = masker.isSensitive(member, key);
            maskedAny = maskedAny || masked;
            Object value = normalize(masker.mask(member, member, key));
            truncated = truncated || InspectValues.isTruncated(value);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index++);
            row.put("member", value);
            rows.add(row);
        }
        if (truncated) {
            warnings.add("集合元素已按单页上限 " + limit + " 截断（Redis 集合预览不做全量拉取）");
        }
        return collectionResult(key, rows, List.of("index", "member"), maskedAny,
                countOf(key, RType.SET), truncated, false, warnings);
    }

    /** zset：ZRANGE WITHSCORES（entryRange） */
    private QueryResult previewZSet(String key, int offset, int pageSize, List<String> warnings) {
        int limit = Math.min(pageSize, maxElements());
        RScoredSortedSet<Object> zset = client.getScoredSortedSet(key);
        Collection<ScoredEntry<Object>> entries;
        try {
            entries = zset.entryRange(offset, offset + limit - 1);
        } catch (Exception e) {
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, rootMessage(e));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean maskedAny = false;
        boolean truncated = entries.size() >= limit;
        int index = offset;
        for (ScoredEntry<Object> entry : entries) {
            String member = String.valueOf(entry.getValue());
            boolean masked = masker.isSensitive(member, key);
            maskedAny = maskedAny || masked;
            Object value = normalize(masker.mask(member, member, key));
            truncated = truncated || InspectValues.isTruncated(value);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", index++);
            row.put("member", value);
            row.put("score", entry.getScore());
            rows.add(row);
        }
        if (truncated) {
            warnings.add("集合元素已按单页上限 " + limit + " 截断（Redis 集合预览不做全量拉取）");
        }
        return collectionResult(key, rows, List.of("index", "member", "score"), maskedAny,
                countOf(key, RType.ZSET), truncated, offset > 0, warnings);
    }

    private QueryResult collectionResult(String key, List<Map<String, Object>> rows, List<String> names,
                                         boolean maskedAny, long total, boolean truncated, boolean paged,
                                         List<String> warnings) {
        List<ColumnItem> columns = new ArrayList<>();
        for (String name : names) {
            columns.add(column(name, maskedAny && !"index".equals(name) && !"score".equals(name)
                    && masker.isSensitive(name, key)));
        }
        if (maskedAny) {
            warnings.add("已脱敏命中敏感名规则的单元格");
        }
        if (paged) {
            warnings.add("集合预览按分页区间取值（offset 已生效）");
        }
        return QueryResult.builder()
                .columns(columns)
                .rows(rows)
                .total(total)
                .truncated(truncated)
                .warnings(warnings)
                .available(true)
                .build();
    }

    // ---------- 工具 ----------

    private long ttlSeconds(String key) {
        try {
            long millis = client.getKeys().remainTimeToLive(key);
            if (millis < 0) {
                return -1;
            }
            return Math.max(0, millis / 1000);
        } catch (Exception e) {
            return -1;
        }
    }

    /** 元素个数（HLEN / LLEN / SCARD / ZCARD / STRLEN） */
    private long elementCount(String key, RType type) {
        try {
            return switch (type) {
                case MAP -> client.getMap(key).size();
                case LIST -> client.getList(key).size();
                case SET -> client.getSet(key).size();
                case ZSET -> client.getScoredSortedSet(key).size();
                case STREAM -> -1;
                default -> {
                    Object value = client.getBucket(key).get();
                    yield value == null ? 0 : String.valueOf(value).length();
                }
            };
        } catch (Exception e) {
            return -1;
        }
    }

    /** MEMORY USAGE（旁路：不支持时为 -1，绝不因它失败） */
    private long memoryUsage(String key) {
        try {
            return client.getBucket(key).sizeInMemory();
        } catch (Exception e) {
            return -1;
        }
    }

    private long countOf(String key, RType type) {
        return elementCount(key, type);
    }

    private Object normalize(Object value) {
        return InspectValues.normalize(value, properties.getRedis().getValueTruncateBytes());
    }

    private int scanCount() {
        return Math.max(1, properties.getRedis().getScanCount());
    }

    private int maxElements() {
        return Math.max(1, properties.getRedis().getMaxElements());
    }

    private String defaultPattern() {
        String pattern = properties.getRedis().getDefaultPattern();
        return (pattern == null || pattern.isBlank()) ? "*" : pattern.trim();
    }

    private ColumnItem column(String name, boolean masked) {
        return ColumnItem.builder().name(name).type("text").masked(masked).build();
    }

    private static String typeName(RType type) {
        if (type == null) {
            return "unknown";
        }
        return switch (type) {
            case OBJECT, JSON, GCRA -> STRING;
            case MAP -> "hash";
            case LIST -> "list";
            case SET -> "set";
            case ZSET -> "zset";
            case STREAM -> "stream";
            default -> type.name().toLowerCase();
        };
    }

    private static String rootMessage(Exception e) {
        Throwable root = e;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return message == null || message.isBlank() ? root.getClass().getSimpleName() : message;
    }
}
