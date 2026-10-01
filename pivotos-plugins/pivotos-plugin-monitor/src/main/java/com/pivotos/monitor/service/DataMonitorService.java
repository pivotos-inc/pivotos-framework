package com.pivotos.monitor.service;

import com.pivotos.starter.datainspect.api.config.DataInspectProperties;
import com.pivotos.starter.datainspect.api.enums.DataSourceType;
import com.pivotos.starter.datainspect.api.enums.RejectReason;
import com.pivotos.starter.datainspect.api.exception.DataInspectException;
import com.pivotos.starter.datainspect.api.model.ComponentSnapshot;
import com.pivotos.starter.datainspect.api.model.PreviewRequest;
import com.pivotos.starter.datainspect.api.model.QueryRequest;
import com.pivotos.starter.datainspect.api.model.QueryResult;
import com.pivotos.starter.datainspect.api.model.SchemaItem;
import com.pivotos.starter.datainspect.api.model.StatsItem;
import com.pivotos.starter.datainspect.api.model.TableItem;
import com.pivotos.starter.datainspect.api.route.DataInspectorFactory;
import com.pivotos.starter.datainspect.api.spi.DataSourceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 通用数据监控（DB / ES / Redis）。
 *
 * <p>降级口径（照抄 EsInfoService）：未启用 / 未引入实现 / 组件不可达 / 采集失败，
 * 一律返回 {@code 200 + code=0 + available=false + reason}，<b>绝不抛异常、绝不 500</b>。
 *
 * <p>权限语义（Controller 层用 {@code @SaCheckPermission} 收口）：
 * <ul>
 *   <li>{@code monitor:data:list} —— 浏览组件与库表树；</li>
 *   <li>{@code monitor:data:preview} —— 预览/分页/统计；</li>
 *   <li>{@code monitor:data:query} —— 自由 SQL（高危，默认只给超管）。</li>
 * </ul>
 * <b>权限只决定能不能点按钮；SQL 安全闸门在执行侧硬生效，超管也不豁免。</b>
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Service
public class DataMonitorService {

    private static final Logger log = LoggerFactory.getLogger(DataMonitorService.class);

    private final ObjectProvider<DataInspectorFactory> factoryProvider;
    private final ObjectProvider<DataInspectProperties> propertiesProvider;

    public DataMonitorService(ObjectProvider<DataInspectorFactory> factoryProvider,
                              ObjectProvider<DataInspectProperties> propertiesProvider) {
        this.factoryProvider = factoryProvider;
        this.propertiesProvider = propertiesProvider;
    }

    /** 组件清单：开关关闭时全部 NOT_ENABLED，未装配的给 IMPL_MISSING 占位（运维可见） */
    public List<ComponentSnapshot> components() {
        DataInspectProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null || !properties.isEnabled()) {
            List<ComponentSnapshot> snapshots = new ArrayList<>();
            for (DataSourceType type : DataSourceType.values()) {
                snapshots.add(ComponentSnapshot.unavailable(type, RejectReason.NOT_ENABLED, null));
            }
            return snapshots;
        }
        DataInspectorFactory factory = factoryProvider.getIfAvailable();
        if (factory == null) {
            List<ComponentSnapshot> snapshots = new ArrayList<>();
            for (DataSourceType type : DataSourceType.values()) {
                snapshots.add(ComponentSnapshot.unavailable(type, RejectReason.IMPL_MISSING,
                        "未引入 pivotos-starter-datainspect"));
            }
            return snapshots;
        }
        return factory.components();
    }

    public List<SchemaItem> schemas(String component) {
        Optional<DataSourceInspector> inspector = resolve(component);
        if (inspector.isEmpty()) {
            return List.of();
        }
        try {
            return inspector.get().listSchemas();
        } catch (Exception e) {
            log.warn("[PivotOS][monitor] 列举库失败，已降级：{}", e.getMessage());
            return List.of();
        }
    }

    /**
     * 列举表 / 索引 / key。
     *
     * @param pattern 过滤 pattern（Redis key 空间可能极大，<b>必须支持收窄</b>，见设计 §11-6）；
     *                其余组件忽略该参数（{@link DataSourceInspector#listTables(String, String)} 默认实现）
     */
    public List<TableItem> tables(String component, String schema, String pattern) {
        Optional<DataSourceInspector> inspector = resolve(component);
        if (inspector.isEmpty()) {
            return List.of();
        }
        try {
            return inspector.get().listTables(schema, pattern);
        } catch (Exception e) {
            log.warn("[PivotOS][monitor] 列举表失败，已降级：{}", e.getMessage());
            return List.of();
        }
    }

    public StatsItem stats(String component, String schema, String table) {
        Optional<DataSourceInspector> inspector = resolve(component);
        if (inspector.isEmpty()) {
            return StatsItem.builder().schema(schema).table(table).rowCount(-1).sizeBytes(-1).build();
        }
        try {
            return inspector.get().stats(schema, table);
        } catch (Exception e) {
            log.warn("[PivotOS][monitor] 统计采集失败，已降级：{}", e.getMessage());
            return StatsItem.builder().schema(schema).table(table).rowCount(-1).sizeBytes(-1).build();
        }
    }

    /** 预览：内部固定语句，不接受用户语句 */
    public QueryResult preview(PreviewRequest request) {
        String component = request == null ? null : request.getComponent();
        Optional<DataSourceInspector> inspector = resolve(component);
        if (inspector.isEmpty()) {
            return QueryResult.unavailable(RejectReason.IMPL_MISSING, "组件 " + component + " 未装配");
        }
        try {
            return inspector.get().preview(request);
        } catch (Exception e) {
            log.warn("[PivotOS][monitor] 预览失败，已降级：{}", e.getMessage());
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, e.getMessage());
        }
    }

    /**
     * 自由查询（高危）：权限在 Controller 层收口，SQL 安全闸门在 Inspector 内部<b>硬生效</b>。
     *
     * <p>审计：由 Controller 上的 {@code @Log(module="数据监控", type=OperType.QUERY)} 落 sys_oper_log
     * （语句原文 + 耗时 + 成败）；返回行数随响应返回，命中慢阈值时另打 WARN 日志。
     */
    public QueryResult query(QueryRequest request) {
        String component = request == null ? null : request.getComponent();
        Optional<DataSourceInspector> inspector = resolve(component);
        if (inspector.isEmpty()) {
            return QueryResult.unavailable(RejectReason.IMPL_MISSING, "组件 " + component + " 未装配");
        }
        try {
            return inspector.get().query(request);
        } catch (Exception e) {
            log.warn("[PivotOS][monitor] 自由查询失败，已降级：{}", e.getMessage());
            return QueryResult.unavailable(RejectReason.COLLECT_FAILED, e.getMessage());
        }
    }

    /**
     * 解析组件：类型不合法直接抛业务异常（400 业务码），未装配返回 empty（由调用方降级）。
     *
     * <p>注意 ObjectProvider 选 Bean 顺序不保证（S127-B 教训），这里显式按 type 取。
     */
    private Optional<DataSourceInspector> resolve(String component) {
        DataSourceType type = DataSourceType.of(component);
        if (type == null) {
            throw new DataInspectException(com.pivotos.starter.datainspect.api.enums.DataInspectErrorCode.COMPONENT_INVALID,
                    "component=" + component);
        }
        DataInspectProperties properties = propertiesProvider.getIfAvailable();
        if (properties == null || !properties.isEnabled()) {
            throw new DataInspectException(com.pivotos.starter.datainspect.api.enums.DataInspectErrorCode.COMPONENT_NOT_FOUND,
                    "能力未启用");
        }
        DataInspectorFactory factory = factoryProvider.getIfAvailable();
        if (factory == null) {
            return Optional.empty();
        }
        return factory.get(type);
    }
}
