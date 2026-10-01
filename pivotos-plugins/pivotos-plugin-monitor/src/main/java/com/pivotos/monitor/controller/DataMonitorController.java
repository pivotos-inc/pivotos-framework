package com.pivotos.monitor.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.result.R;
import com.pivotos.monitor.service.DataMonitorService;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.starter.datainspect.api.model.ComponentSnapshot;
import com.pivotos.starter.datainspect.api.model.PreviewRequest;
import com.pivotos.starter.datainspect.api.model.QueryRequest;
import com.pivotos.starter.datainspect.api.model.QueryResult;
import com.pivotos.starter.datainspect.api.model.SchemaItem;
import com.pivotos.starter.datainspect.api.model.StatsItem;
import com.pivotos.starter.datainspect.api.model.TableItem;
import com.pivotos.system.api.annotation.Log;
import com.pivotos.system.api.enums.OperType;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 通用数据监控（DB / ES / Redis）。
 *
 * <p>权限分层（sys_menu 'F' 按钮，见 Flyway V1.2.51）：
 * <ul>
 *   <li>{@code monitor:data:list} —— 浏览组件与库表；</li>
 *   <li>{@code monitor:data:preview} —— 预览 / 分页 / 统计；</li>
 *   <li>{@code monitor:data:query} —— 自由 SQL（高危，默认只给超管）。</li>
 * </ul>
 *
 * <p><b>铁律</b>：权限只决定「能不能点按钮 / 能不能看到输入框」；
 * SQL 安全闸门（语句类型白名单、表白名单、强制 LIMIT、超时熔断、租户改写、敏感列脱敏）在
 * {@code DataSourceInspector} 执行侧<b>硬生效</b>，与权限无关，超管也不豁免。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Tag(name = "通用数据监控", description = "DB / ES / Redis 库表浏览、预览与自由 SQL（只读）")
@RestController
@RequestMapping("/monitor/data")
@RequiredArgsConstructor
public class DataMonitorController {

    private final DataMonitorService dataMonitorService;

    @Operation(summary = "组件清单（含不可用原因与能力集）")
    @GetMapping("/components")
    @SaCheckPermission(value = "monitor:data:list", type = StpSysUtil.TYPE)
    public R<List<ComponentSnapshot>> components() {
        return R.ok(dataMonitorService.components());
    }

    @Operation(summary = "列举库 / 索引分组 / Redis db")
    @GetMapping("/schemas")
    @SaCheckPermission(value = "monitor:data:list", type = StpSysUtil.TYPE)
    public R<List<SchemaItem>> schemas(@RequestParam String component) {
        return R.ok(dataMonitorService.schemas(component));
    }

    @Operation(summary = "列举表 / 索引 / key")
    @GetMapping("/tables")
    @SaCheckPermission(value = "monitor:data:list", type = StpSysUtil.TYPE)
    public R<List<TableItem>> tables(@RequestParam String component,
                                     @RequestParam(required = false) String schema,
                                     @RequestParam(required = false) String pattern) {
        return R.ok(dataMonitorService.tables(component, schema, pattern));
    }

    @Operation(summary = "统计信息")
    @GetMapping("/stats")
    @SaCheckPermission(value = "monitor:data:preview", type = StpSysUtil.TYPE)
    public R<StatsItem> stats(@RequestParam String component,
                              @RequestParam(required = false) String schema,
                              @RequestParam(required = false) String table) {
        return R.ok(dataMonitorService.stats(component, schema, table));
    }

    @Operation(summary = "分页预览（内部固定语句，不接受用户语句）")
    @PostMapping("/preview")
    @SaCheckPermission(value = "monitor:data:preview", type = StpSysUtil.TYPE)
    @Log(module = "数据监控", type = OperType.QUERY)
    public R<QueryResult> preview(@RequestBody PreviewRequest request) {
        return R.ok(dataMonitorService.preview(request));
    }

    @Operation(summary = "自由 SQL / DSL 执行（高危：仅只读语句，闸门硬生效）")
    @PostMapping("/query")
    @SaCheckPermission(value = "monitor:data:query", type = StpSysUtil.TYPE)
    @Log(module = "数据监控", type = OperType.QUERY)
    public R<QueryResult> query(@RequestBody QueryRequest request) {
        return R.ok(dataMonitorService.query(request));
    }
}
