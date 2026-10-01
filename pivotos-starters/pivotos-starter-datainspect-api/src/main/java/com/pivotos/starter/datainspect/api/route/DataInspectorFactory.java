package com.pivotos.starter.datainspect.api.route;

import com.pivotos.starter.datainspect.api.enums.DataSourceType;
import com.pivotos.starter.datainspect.api.enums.RejectReason;
import com.pivotos.starter.datainspect.api.model.ComponentSnapshot;
import com.pivotos.starter.datainspect.api.spi.DataSourceInspector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 数据监控组件路由工厂（照抄 {@code SearchProviderFactory} 套路，语义上有且仅有一处差异）。
 *
 * <p>差异：检索是「多个实现选一个生效」，数据监控是「多个组件<b>并存</b>」——
 * 因此这里不做实现回落，缺席/不可达的组件以 {@code available=false + reason} 出现在清单里，
 * 让运维一眼看出「支持但未接入」还是「接入了但连不上」。
 *
 * <p>共同点（照抄）：{@code List} 注入 + {@code EnumMap} 按 {@code type()} 索引 + 纯 POJO 可单测
 * + {@link #registeredTypes()} 运维可见。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public class DataInspectorFactory {

    private static final Logger log = LoggerFactory.getLogger(DataInspectorFactory.class);

    private final Map<DataSourceType, DataSourceInspector> inspectors = new EnumMap<>(DataSourceType.class);

    public DataInspectorFactory(List<DataSourceInspector> providers) {
        if (providers != null) {
            for (DataSourceInspector inspector : providers) {
                DataSourceType type = inspector.type();
                DataSourceInspector previous = inspectors.put(type, inspector);
                if (previous != null) {
                    log.warn("[PivotOS][datainspect] 组件 {} 存在多个实现，已取后注册者", type.getCode());
                }
            }
        }
        log.info("[PivotOS][datainspect] 已装配组件 {}（扩展点占位 {}）",
                inspectors.keySet().stream().map(DataSourceType::getCode).collect(Collectors.toList()),
                unregisteredTypes().stream().map(DataSourceType::getCode).collect(Collectors.toList()));
    }

    public Optional<DataSourceInspector> get(DataSourceType type) {
        return type == null ? Optional.empty() : Optional.ofNullable(inspectors.get(type));
    }

    public Optional<DataSourceInspector> get(String code) {
        DataSourceType type = DataSourceType.of(code);
        if (type == null) {
            log.warn("[PivotOS][datainspect] 组件类型不合法：{}", code);
        }
        return get(type);
    }

    /** 已装配实现（运维可见） */
    public List<DataSourceType> registeredTypes() {
        return new ArrayList<>(inspectors.keySet());
    }

    /** 支持但未接入的类型（扩展点占位） */
    public List<DataSourceType> unregisteredTypes() {
        List<DataSourceType> all = new ArrayList<>(List.of(DataSourceType.values()));
        all.removeAll(inspectors.keySet());
        return all;
    }

    /**
     * 全部组件快照：已装配的走 {@code snapshot()}，未装配的给 IMPL_MISSING 占位。
     * <b>永不抛异常</b>——单个组件采集炸了只让它自己降级。
     */
    public List<ComponentSnapshot> components() {
        List<ComponentSnapshot> snapshots = new ArrayList<>();
        for (DataSourceType type : DataSourceType.values()) {
            DataSourceInspector inspector = inspectors.get(type);
            if (inspector == null) {
                snapshots.add(ComponentSnapshot.unavailable(type, RejectReason.IMPL_MISSING,
                        "未引入 " + type.getCode() + " 实现模块"));
                continue;
            }
            try {
                snapshots.add(inspector.snapshot());
            } catch (Exception e) {
                log.warn("[PivotOS][datainspect] 组件 {} 快照采集异常，已降级：{}", type.getCode(), e.getMessage());
                snapshots.add(ComponentSnapshot.unavailable(type, RejectReason.COLLECT_FAILED, e.getMessage()));
            }
        }
        return snapshots;
    }
}
