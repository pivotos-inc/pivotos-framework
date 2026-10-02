package com.pivotos.starter.datainspect.api.spi;

import com.pivotos.starter.datainspect.api.enums.Capability;
import com.pivotos.starter.datainspect.api.enums.DataSourceType;
import com.pivotos.starter.datainspect.api.model.ComponentSnapshot;
import com.pivotos.starter.datainspect.api.model.PreviewRequest;
import com.pivotos.starter.datainspect.api.model.QueryRequest;
import com.pivotos.starter.datainspect.api.model.QueryResult;
import com.pivotos.starter.datainspect.api.model.SchemaItem;
import com.pivotos.starter.datainspect.api.model.StatsItem;
import com.pivotos.starter.datainspect.api.model.TableItem;

import java.util.List;
import java.util.Set;

/**
 * 通用数据监控 SPI：一个组件一个实现，多组件<b>并存</b>（与检索的「选一个生效」不同）。
 *
 * <p>契约（照抄 SearchHealthProvider）：
 * <ul>
 *   <li><b>任何情况下都不抛异常</b>：不可用返回 {@code available=false + reason}，由 Controller 原样返回 200 + code=0；</li>
 *   <li>自由查询必须先过安全闸门再执行——<b>权限只管入口，闸门管执行</b>，二者不可互相替代；</li>
 *   <li>元数据（库表清单）天然跨租户，租户语义由实现显式声明（见各实现的类注释）。</li>
 * </ul>
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public interface DataSourceInspector {

    /** 组件类型 */
    DataSourceType type();

    /** 能力集：前端按能力裁剪 UI */
    Set<Capability> capabilities();

    /** 组件是否可用（连接可达、依赖就绪） */
    boolean isAvailable();

    /** 组件快照（含不可用原因） */
    ComponentSnapshot snapshot();

    /** 列举库 / 索引分组 / db */
    List<SchemaItem> listSchemas();

    /** 列举表 / 索引 / key */
    List<TableItem> listTables(String schema);

    /**
     * 列举表 / 索引 / key（带过滤 pattern）。
     *
     * <p>存在理由：Redis 的 key 空间可能极大，<b>必须让用户能用 pattern 收窄</b>（设计 §11-6 明列风险），
     * 否则一次 SCAN 就能把监控页和 Redis 一起拖慢。默认实现忽略 pattern（MySQL / ES 走这条），
     * 只有 Redis 覆写它——给 SPI 增方法必须给 default，否则既有实现全部编译失败（S128 教训）。
     */
    default List<TableItem> listTables(String schema, String pattern) {
        return listTables(schema);
    }

    /** 分页预览：内部固定语句，不接受用户语句 */
    QueryResult preview(PreviewRequest request);

    /** 自由查询：必须过安全闸门；无 QUERY 能力的组件直接返回不可用 */
    QueryResult query(QueryRequest request);

    /** 统计信息 */
    StatsItem stats(String schema, String table);
}
