package com.pivotos.starter.datainspect.mysql;

import com.pivotos.starter.core.context.TenantContext;
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
import com.pivotos.starter.datainspect.api.security.GuardContext;
import com.pivotos.starter.datainspect.api.security.GuardResult;
import com.pivotos.starter.datainspect.api.security.SqlGuard;
import com.pivotos.starter.datainspect.api.spi.DataSourceInspector;
import com.pivotos.starter.datainspect.security.ColumnMasker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * MySQL 数据监控实现：<b>只读</b>（information_schema 元数据列举 + 单表预览 + 自由 SQL）。
 *
 * <p>租户语义（显式声明）：
 * <ul>
 *   <li><b>元数据天然跨租户</b>：库表清单属于平台运维视图，不按租户裁剪，靠 {@code monitor:data:list} 权限收口；</li>
 *   <li><b>数据行按租户改写</b>：预览与自由 SQL 对「非内置忽略表且确实存在 tenant_id 列」的表追加
 *       {@code tenant_id = ?}，<b>超管（{@code *:*:*}）也不例外</b>——裸 JDBC 不经过 MyBatis 租户拦截器，
 *       这里必须自己补上，否则就是越权读取全租户数据；</li>
 *   <li>数据权限（{@code @DataScope} 的部门/用户维度）<b>不适用于本通道</b>：它是 Mapper 级插件，
 *       裸 SQL 不触发。本通道以「租户改写 + 库表白名单 + 权限码」三件套替代。</li>
 * </ul>
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public class MySqlInspector implements DataSourceInspector {

    private static final Logger log = LoggerFactory.getLogger(MySqlInspector.class);

    private static final Set<Capability> CAPABILITIES = Set.of(
            Capability.LIST_SCHEMAS, Capability.LIST_TABLES, Capability.PREVIEW, Capability.QUERY, Capability.STATS);

    /** 系统库：不在库表树中暴露（元数据由组件内部固定 SQL 访问） */
    private static final Set<String> SYSTEM_SCHEMAS = Set.of(
            "information_schema", "mysql", "performance_schema", "sys");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z0-9_$]+");

    /** 单列超长文本截断长度 */
    private static final int VALUE_MAX_LENGTH = 2000;

    private final DataSource dataSource;
    private final DataInspectProperties properties;
    private final SqlGuard guard;
    private final Supplier<Set<String>> ignoreTablesSupplier;
    private final ColumnMasker masker;

    /** 启动/首次探测结果缓存：运行期不反复发起连接探测 */
    private volatile Boolean available;
    private volatile String unavailableDetail;

    /** 表是否含租户列（一次查询、长期缓存，避免每条语句都查 information_schema） */
    private final Map<String, Boolean> tenantColumnCache = new ConcurrentHashMap<>();

    public MySqlInspector(DataSource dataSource, DataInspectProperties properties, SqlGuard guard,
                          Supplier<Set<String>> ignoreTablesSupplier) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.guard = guard;
        this.ignoreTablesSupplier = ignoreTablesSupplier;
        this.masker = new ColumnMasker(properties == null ? null : properties.getMaskColumnRegex());
    }

    // ---------- SPI 基础 ----------

    @Override
    public DataSourceType type() {
        return DataSourceType.MYSQL;
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
            return ComponentSnapshot.unavailable(DataSourceType.MYSQL, RejectReason.UNREACHABLE, unavailableDetail);
        }
        return ComponentSnapshot.of(DataSourceType.MYSQL, true, null, "MySQL 已连接", CAPABILITIES);
    }

    private void probe() {
        try (Connection connection = dataSource.getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT 1")) {
                statement.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
                statement.executeQuery();
            }
            available = true;
            unavailableDetail = null;
        } catch (Exception e) {
            available = false;
            unavailableDetail = e.getMessage();
            log.warn("[PivotOS][datainspect] MySQL 组件不可用，全部接口走降级：{}", e.getMessage());
        }
    }

    // ---------- 元数据 ----------

    @Override
    public List<SchemaItem> listSchemas() {
        if (!isAvailable()) {
            return List.of();
        }
        StringBuilder sql = new StringBuilder(
                "SELECT s.schema_name AS name, COUNT(t.table_name) AS item_count "
                        + "FROM information_schema.schemata s "
                        + "LEFT JOIN information_schema.tables t ON t.table_schema = s.schema_name "
                        + "WHERE s.schema_name NOT IN ('information_schema','mysql','performance_schema','sys') ");
        List<Object> params = new ArrayList<>();
        List<String> whitelist = schemaWhitelist();
        if (!whitelist.isEmpty()) {
            sql.append("AND s.schema_name IN (");
            for (int i = 0; i < whitelist.size(); i++) {
                sql.append(i == 0 ? "?" : ",?");
                params.add(whitelist.get(i));
            }
            sql.append(") ");
        }
        sql.append("GROUP BY s.schema_name ORDER BY s.schema_name");
        return executeForList(sql.toString(), params, this::toSchema);
    }

    @Override
    public List<TableItem> listTables(String schema) {
        if (!isAvailable() || !validIdentifier(schema)) {
            return List.of();
        }
        // 显式别名：① information_schema 的列标签是 TABLE_NAME 形态，别名让取值不依赖大小写；
        //           ② 别避开 MySQL 保留字（ROWS 是 MySQL 8 保留字，直接 AS rows 会语法报错）
        String sql = "SELECT table_name AS name, table_type AS type, table_rows AS row_count, "
                + "table_comment AS comment "
                + "FROM information_schema.tables WHERE table_schema = ? "
                + "ORDER BY table_name LIMIT ?";
        List<TableItem> items = executeForList(sql, List.of(schema, maxItems()), this::toTable);
        items.forEach(item -> item.setSchema(schema));
        return items;
    }

    @Override
    public StatsItem stats(String schema, String table) {
        if (!isAvailable() || !validIdentifier(schema) || !validIdentifier(table)) {
            return StatsItem.builder().schema(schema).table(table).rowCount(-1).sizeBytes(-1).build();
        }
        String sql = "SELECT table_rows, data_length + index_length AS size_bytes, engine, table_comment "
                + "FROM information_schema.tables WHERE table_schema = ? AND table_name = ?";
        List<Map<String, Object>> rows = executeForList(sql, List.of(schema, table), r -> r);
        if (rows.isEmpty()) {
            return StatsItem.builder().schema(schema).table(table).rowCount(-1).sizeBytes(-1).build();
        }
        Map<String, Object> row = rows.get(0);
        return StatsItem.builder()
                .schema(schema)
                .table(table)
                .rowCount(toLong(row.get("table_rows"), -1))
                .sizeBytes(toLong(row.get("size_bytes"), -1))
                .engine(str(row.get("engine")))
                .extra(Map.of("comment", str(row.get("table_comment"))))
                .build();
    }

    // ---------- 数据 ----------

    @Override
    public QueryResult preview(PreviewRequest request) {
        long start = System.currentTimeMillis();
        if (!isAvailable()) {
            return QueryResult.unavailable(RejectReason.UNREACHABLE, unavailableDetail);
        }
        String schema = request == null ? null : request.getSchema();
        String table = request == null ? null : request.getTable();
        if (!validIdentifier(schema) || !validIdentifier(table)) {
            return QueryResult.unavailable(RejectReason.FORBIDDEN, "库名/表名不合法");
        }
        if (!isTableAllowed(table)) {
            return QueryResult.unavailable(RejectReason.FORBIDDEN,
                    "表不在白名单内：" + table + "（如需放开请配置 pivotos.datainspect.mysql.table-whitelist）");
        }
        int pageSize = request.getPageSize() <= 0 ? 20 : Math.min(request.getPageSize(), hardMaxRows());
        int pageNum = request.getPageNum() <= 0 ? 1 : request.getPageNum();
        int offset = (pageNum - 1) * pageSize;
        List<String> warnings = new ArrayList<>();

        String where = tenantCondition(schema, table, warnings);
        String countSql = "SELECT COUNT(*) AS total FROM `" + schema + "`.`" + table + "`" + where;
        long total = -1;
        try {
            List<Map<String, Object>> counts = executeForList(countSql, List.of(), r -> r);
            if (!counts.isEmpty()) {
                total = toLong(counts.get(0).get("total"), -1);
            }
        } catch (Exception e) {
            log.warn("[PivotOS][datainspect] 预览统计总数失败，按未知总数返回：{}", e.getMessage());
        }

        String dataSql = "SELECT * FROM `" + schema + "`.`" + table + "`" + where
                + " LIMIT " + pageSize + " OFFSET " + offset;
        QueryResult result = execute(dataSql, List.of(), pageSize);
        result.setTotal(total);
        result.setDurationMs(System.currentTimeMillis() - start);
        if (result.getWarnings() == null) {
            result.setWarnings(new ArrayList<>());
        }
        result.getWarnings().addAll(warnings);
        return result;
    }

    @Override
    public QueryResult query(QueryRequest request) {
        long start = System.currentTimeMillis();
        if (!isAvailable()) {
            return QueryResult.unavailable(RejectReason.UNREACHABLE, unavailableDetail);
        }
        if (properties != null && !properties.isQueryEnabled()) {
            return QueryResult.unavailable(RejectReason.QUERY_DISABLED, null);
        }
        String statement = request == null ? null : request.getStatement();
        int maxRows = request == null || request.getMaxRows() == null || request.getMaxRows() <= 0
                ? properties.getMaxRows() : request.getMaxRows();

        // 先解析出表名，用于判定租户列是否存在（闸门需要这个输入）
        String schema = request == null ? null : request.getSchema();
        Boolean hasTenantColumn = null;
        if (validIdentifier(schema) && statement != null) {
            String guessed = guessTable(statement);
            if (guessed != null) {
                hasTenantColumn = hasTenantColumn(schema, guessed);
            }
        }

        GuardResult guardResult = guard.inspect(statement, GuardContext.builder()
                .maxRows(maxRows)
                .hardMaxRows(hardMaxRows())
                .tableWhitelist(Set.copyOf(tableWhitelist()))
                .allowAllTables(properties.getMysql().isAllowAllTables())
                .tenantId(TenantContext.get())
                .tenantIdColumn(properties.getMysql().getTenantIdColumn())
                .tenantIgnoreTables(ignoreTables())
                .forceTenantScope(properties.isForceTenantScope())
                .tableHasTenantColumn(hasTenantColumn)
                .build());
        if (!guardResult.isPassed()) {
            log.warn("[PivotOS][datainspect] 自由 SQL 被安全闸门拒绝：{} / {}",
                    guardResult.getRejectCode(), guardResult.getRejectMessage());
            return QueryResult.unavailable(RejectReason.FORBIDDEN, guardResult.getRejectMessage());
        }

        QueryResult result = execute(guardResult.getNormalizedSql(), List.of(), guardResult.getEffectiveMaxRows());
        result.setDurationMs(System.currentTimeMillis() - start);
        result.getWarnings().addAll(guardResult.getWarnings());
        return result;
    }

    // ---------- 执行与兜底 ----------

    /**
     * 执行只读查询：只读连接 + 超时熔断 + 行数上限 + 敏感列脱敏；<b>任何异常都不外抛</b>。
     */
    private QueryResult execute(String sql, List<Object> params, int maxRows) {
        List<ColumnItem> columns = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        long start = System.currentTimeMillis();
        boolean truncated = false;
        try (Connection connection = dataSource.getConnection()) {
            try {
                connection.setReadOnly(true);
            } catch (Exception e) {
                // MySQL 驱动在部分事务状态下不支持切换只读：不阻断，只读性由「语句白名单 + 闸门」保证
                log.debug("[PivotOS][datainspect] 设置只读连接失败，已忽略：{}", e.getMessage());
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setQueryTimeout(Math.max(1, properties.getQueryTimeoutSeconds()));
                bind(statement, params);
                statement.setFetchSize(Math.min(Math.max(maxRows, 1), 1000));
                try (ResultSet resultSet = statement.executeQuery()) {
                    ResultSetMetaData metaData = resultSet.getMetaData();
                    int count = metaData.getColumnCount();
                    boolean[] masked = new boolean[count];
                    for (int i = 1; i <= count; i++) {
                        String label = metaData.getColumnLabel(i);
                        masked[i - 1] = properties.isMaskEnabled() && masker.isSensitive(label);
                        columns.add(ColumnItem.builder()
                                .name(label)
                                .type(metaData.getColumnTypeName(i))
                                .masked(masked[i - 1])
                                .build());
                    }
                    int maskedCount = 0;
                    while (resultSet.next()) {
                        if (rows.size() >= maxRows) {
                            truncated = true;
                            break;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        for (int i = 1; i <= count; i++) {
                            String label = metaData.getColumnLabel(i);
                            Object value = convert(resultSet.getObject(i));
                            if (masked[i - 1]) {
                                value = masker.mask(label, value);
                                maskedCount++;
                            }
                            row.put(label, value);
                        }
                        rows.add(row);
                    }
                    if (maskedCount > 0) {
                        warnings.add("已脱敏 " + maskedCount + " 个单元格（命中敏感列名规则）");
                    }
                    if (truncated) {
                        warnings.add("结果已截断，仅返回前 " + maxRows + " 行");
                    }
                }
            }
        } catch (SQLException e) {
            log.warn("[PivotOS][datainspect] MySQL 查询失败，已降级：{}", e.getMessage());
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, e.getMessage());
        }
        long duration = System.currentTimeMillis() - start;
        if (duration > properties.getSlowThresholdMs()) {
            log.warn("[PivotOS][datainspect] 慢语句：{}ms > {}ms | {}", duration, properties.getSlowThresholdMs(), sql);
            warnings.add("慢语句：" + duration + "ms（阈值 " + properties.getSlowThresholdMs() + "ms）");
        }
        return QueryResult.builder()
                .columns(columns)
                .rows(rows)
                .total(rows.size())
                .truncated(truncated)
                .durationMs(duration)
                .warnings(warnings)
                .available(true)
                .build();
    }

    private <T> List<T> executeForList(String sql, List<Object> params, RowMapper<T> mapper) {
        QueryResult result = execute(sql, params, maxItems());
        List<T> list = new ArrayList<>();
        if (result.getRows() == null) {
            return list;
        }
        for (Map<String, Object> row : result.getRows()) {
            T mapped = mapper.map(row);
            if (mapped != null) {
                list.add(mapped);
            }
        }
        return list;
    }

    private void bind(PreparedStatement statement, List<Object> params) throws SQLException {
        if (params == null) {
            return;
        }
        for (int i = 0; i < params.size(); i++) {
            statement.setObject(i + 1, params.get(i));
        }
    }

    /** 结果值归一：二进制与大文本一律收敛，避免前端渲染爆炸 */
    private Object convert(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof byte[] bytes) {
            return "<binary:" + bytes.length + " bytes>";
        }
        if (value instanceof String text) {
            return text.length() > VALUE_MAX_LENGTH ? text.substring(0, VALUE_MAX_LENGTH) + "…(truncated)" : text;
        }
        return value;
    }

    // ---------- 租户与白名单 ----------

    /** 预览的租户条件（与自由 SQL 同一口径：非忽略表且存在租户列才追加） */
    private String tenantCondition(String schema, String table, List<String> warnings) {
        if (!properties.isForceTenantScope()) {
            return "";
        }
        Long tenantId = TenantContext.get();
        if (tenantId == null) {
            return "";
        }
        if (ignoreTables().contains(table.toLowerCase(Locale.ROOT))) {
            return "";
        }
        if (!hasTenantColumn(schema, table)) {
            return "";
        }
        warnings.add("已按租户改写（" + properties.getMysql().getTenantIdColumn() + "=" + tenantId + "）");
        return " WHERE `" + properties.getMysql().getTenantIdColumn() + "` = " + tenantId;
    }

    private boolean hasTenantColumn(String schema, String table) {
        String key = schema + "." + table;
        Boolean cached = tenantColumnCache.get(key);
        if (cached != null) {
            return cached;
        }
        boolean exists;
        try {
            String sql = "SELECT COUNT(*) AS c FROM information_schema.columns "
                    + "WHERE table_schema = ? AND table_name = ? AND column_name = ?";
            List<Map<String, Object>> rows = executeForList(sql,
                    List.of(schema, table, properties.getMysql().getTenantIdColumn()), r -> r);
            exists = !rows.isEmpty() && toLong(rows.get(0).get("c"), 0) > 0;
        } catch (Exception e) {
            // 查不到元数据时按「有租户列」处理更保守（宁可多过滤导致查不到，也不越权放开）
            exists = true;
            log.warn("[PivotOS][datainspect] 读取列元数据失败，按存在租户列处理：{}", e.getMessage());
        }
        tenantColumnCache.put(key, exists);
        return exists;
    }

    /** 从语句里粗提取表名（仅用于判定租户列，不用于安全判定——安全判定在闸门里） */
    private String guessTable(String statement) {
        if (statement == null) {
            return null;
        }
        String lower = statement.toLowerCase(Locale.ROOT);
        int from = lower.indexOf("from");
        if (from < 0) {
            return null;
        }
        String rest = statement.substring(from + 4).trim();
        StringBuilder name = new StringBuilder();
        for (char c : rest.toCharArray()) {
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$' || c == '.') {
                name.append(c);
            } else {
                break;
            }
        }
        String candidate = name.toString().replace("`", "");
        int dot = candidate.indexOf('.');
        return dot > 0 ? candidate.substring(dot + 1) : candidate;
    }

    private boolean isTableAllowed(String table) {
        if (properties.getMysql().isAllowAllTables()) {
            return true;
        }
        for (String allowed : tableWhitelist()) {
            String a = allowed.trim().toLowerCase(Locale.ROOT);
            if (a.equals(table.toLowerCase(Locale.ROOT)) || a.endsWith("." + table.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private Set<String> ignoreTables() {
        return ignoreTablesSupplier == null ? Set.of() : ignoreTablesSupplier.get();
    }

    private List<String> tableWhitelist() {
        return properties.getMysql().getTableWhitelist() == null
                ? List.of() : properties.getMysql().getTableWhitelist();
    }

    private List<String> schemaWhitelist() {
        return properties.getMysql().getSchemaWhitelist() == null
                ? List.of() : properties.getMysql().getSchemaWhitelist();
    }

    private int maxItems() {
        return Math.max(1, properties.getMaxRows());
    }

    private int hardMaxRows() {
        return properties.getHardMaxRows() <= 0 ? 1000 : properties.getHardMaxRows();
    }

    private static boolean validIdentifier(String value) {
        return value != null && IDENTIFIER.matcher(value).matches();
    }

    private static long toLong(Object value, long defaultValue) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private SchemaItem toSchema(Map<String, Object> row) {
        String name = str(row.get("name"));
        return SchemaItem.builder()
                .name(name)
                .label(name)
                .itemCount(toLong(row.get("item_count"), 0))
                .build();
    }

    private TableItem toTable(Map<String, Object> row) {
        String name = str(row.get("name"));
        return TableItem.builder()
                .schema(str(row.get("schema")))
                .name(name)
                .type(str(row.get("type")).equalsIgnoreCase("VIEW") ? "view" : "table")
                .comment(str(row.get("comment")))
                .rowCount(toLong(row.get("row_count"), -1))
                .ttl(-1)
                .build();
    }

    /** 行映射函数（内部用，避免引 spring-jdbc 依赖） */
    private interface RowMapper<T> {
        T map(Map<String, Object> row);
    }
}
