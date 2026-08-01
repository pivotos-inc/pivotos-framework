package com.pivotos.generator.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.result.R;
import com.pivotos.generator.domain.entity.GenTable;
import com.pivotos.generator.domain.entity.GenTableColumn;
import com.pivotos.generator.service.GeneratorService;
import com.pivotos.starter.auth.account.StpSysUtil;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 代码生成器 - 控制器
 */
@RestController
@RequestMapping("/generator")
@RequiredArgsConstructor
public class GeneratorController {

    private final GeneratorService generatorService;

    // ==================== 数据库表管理 ====================

    /** 查询数据库表列表（information_schema） */
    @GetMapping("/db/list")
    @SaCheckPermission(value = "generator:db:list", type = StpSysUtil.TYPE)
    public R<IPage<Map<String, Object>>> selectDbTableList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String tableComment) {
        Page<Map<String, Object>> page = new Page<>(pageNum, pageSize);
        return R.ok(generatorService.selectDbTableList(page, tableName, tableComment));
    }

    /** 导入表结构 */
    @PostMapping("/import")
    @SaCheckPermission(value = "generator:gen:import", type = StpSysUtil.TYPE)
    public R<Void> importTable(@Valid @RequestBody ImportTableRequest request) {
        generatorService.importTable(request.getTableNames(),
                request.getPackageName(), request.getModuleName(),
                request.getBusinessName(), request.getFunctionName(),
                request.getFunctionAuthor());
        return R.ok();
    }

    // ==================== 生成表管理 ====================

    /** 分页查询已导入的生成表 */
    @GetMapping("/list")
    @SaCheckPermission(value = "generator:gen:list", type = StpSysUtil.TYPE)
    public R<IPage<GenTable>> selectGenTableList(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestParam(required = false) String tableName,
            @RequestParam(required = false) String tableComment) {
        Page<GenTable> page = new Page<>(pageNum, pageSize);
        return R.ok(generatorService.selectGenTableList(page, tableName, tableComment));
    }

    /** 查询生成表详情 */
    @GetMapping("/{tableId}")
    @SaCheckPermission(value = "generator:gen:query", type = StpSysUtil.TYPE)
    public R<GenTable> getInfo(@PathVariable Long tableId) {
        return R.ok(generatorService.selectGenTableById(tableId));
    }

    /** 删除生成表 */
    @DeleteMapping("/{tableIds}")
    @SaCheckPermission(value = "generator:gen:remove", type = StpSysUtil.TYPE)
    public R<Void> remove(@PathVariable List<Long> tableIds) {
        generatorService.deleteGenTable(tableIds);
        return R.ok();
    }

    /** 同步数据库表字段 */
    @PutMapping("/synch/{tableId}")
    @SaCheckPermission(value = "generator:gen:synch", type = StpSysUtil.TYPE)
    public R<Void> synchDb(@PathVariable Long tableId) {
        generatorService.synchDb(tableId);
        return R.ok();
    }

    // ==================== 字段管理 ====================

    /** 查询表的字段列表 */
    @GetMapping("/column/{tableId}")
    @SaCheckPermission(value = "generator:gen:list", type = StpSysUtil.TYPE)
    public R<List<GenTableColumn>> selectColumnList(@PathVariable Long tableId) {
        return R.ok(generatorService.selectGenTableColumnListByTableId(tableId));
    }

    /** 更新字段配置 */
    @PutMapping("/column")
    @SaCheckPermission(value = "generator:gen:edit", type = StpSysUtil.TYPE)
    public R<Void> updateColumn(@Valid @RequestBody GenTableColumn column) {
        generatorService.updateGenTableColumn(column);
        return R.ok();
    }

    // ==================== 代码生成 ====================

    /** 预览代码 */
    @GetMapping("/preview/{tableId}")
    @SaCheckPermission(value = "generator:gen:preview", type = StpSysUtil.TYPE)
    public R<Map<String, String>> preview(@PathVariable Long tableId) {
        return R.ok(generatorService.previewCode(tableId));
    }

    /** 下载代码（zip） */
    @GetMapping("/download/{tableId}")
    @SaCheckPermission(value = "generator:gen:generate", type = StpSysUtil.TYPE)
    public void download(@PathVariable Long tableId, HttpServletResponse response) throws IOException {
        GenTable table = generatorService.selectGenTableById(tableId);
        byte[] data = generatorService.downloadCode(tableId);
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition",
                "attachment; filename=" + table.getTableName() + ".zip");
        response.getOutputStream().write(data);
    }

    /** 生成代码并写入工程 */
    @PostMapping("/generate/{tableId}")
    @SaCheckPermission(value = "generator:gen:generate", type = StpSysUtil.TYPE)
    public R<Void> generate(@PathVariable Long tableId) {
        generatorService.generateToProject(tableId);
        return R.ok();
    }

    // ==================== DTO ====================

    @lombok.Data
    public static class ImportTableRequest {
        @NotEmpty(message = "表名不能为空")
        private List<String> tableNames;
        private String packageName = "com.pivotos.system";
        private String moduleName = "system";
        private String businessName;
        private String functionName;
        private String functionAuthor = "PivotOS";
    }
}
