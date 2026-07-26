package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.convert.DictDataConvert;
import com.pivotos.system.domain.dto.DictDataQuery;
import com.pivotos.system.domain.dto.DictDataSaveRequest;
import com.pivotos.system.domain.dto.DictTypeQuery;
import com.pivotos.system.domain.dto.DictTypeSaveRequest;
import com.pivotos.system.domain.vo.DictDataVO;
import com.pivotos.system.domain.vo.DictTypeVO;
import com.pivotos.system.service.DictDataService;
import com.pivotos.system.service.DictTypeService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 字典管理（类型 + 数据） */
@RestController
@RequestMapping("/system/dict")
@RequiredArgsConstructor
public class DictController {

    private final DictTypeService dictTypeService;
    private final DictDataService dictDataService;
    private final DictDataConvert dictDataConvert;

    // ---------- 字典类型 ----------

    @GetMapping("/type/page")
    @SaCheckPermission(value = "system:dict:list", type = StpSysUtil.TYPE)
    public R<PageResult<DictTypeVO>> typePage(DictTypeQuery query) {
        return R.ok(dictTypeService.pageTypes(query));
    }

    @GetMapping("/type/{id}")
    @SaCheckPermission(value = "system:dict:query", type = StpSysUtil.TYPE)
    public R<DictTypeVO> getType(@PathVariable Long id) {
        return R.ok(dictTypeService.getType(id));
    }

    @PostMapping("/type")
    @SaCheckPermission(value = "system:dict:add", type = StpSysUtil.TYPE)
    public R<Long> createType(@Validated @RequestBody DictTypeSaveRequest request) {
        return R.ok(dictTypeService.createType(request));
    }

    @PutMapping("/type")
    @SaCheckPermission(value = "system:dict:edit", type = StpSysUtil.TYPE)
    public R<Void> updateType(@Validated @RequestBody DictTypeSaveRequest request) {
        dictTypeService.updateType(request);
        return R.ok();
    }

    @DeleteMapping("/type/{id}")
    @SaCheckPermission(value = "system:dict:remove", type = StpSysUtil.TYPE)
    public R<Void> deleteType(@PathVariable Long id) {
        dictTypeService.deleteType(id);
        return R.ok();
    }

    // ---------- 字典数据 ----------

    @GetMapping("/data/page")
    @SaCheckPermission(value = "system:dict:list", type = StpSysUtil.TYPE)
    public R<PageResult<DictDataVO>> dataPage(DictDataQuery query) {
        return R.ok(dictDataService.pageData(query));
    }

    /** 按类型取正常字典项（前端字典翻译，登录即可读） */
    @GetMapping("/data/type/{dictType}")
    @SaCheckLogin(type = StpSysUtil.TYPE)
    public R<List<DictDataVO>> dataByType(@PathVariable String dictType) {
        return R.ok(dictDataConvert.toVoList(dictDataService.listEnabledByType(dictType)));
    }

    @GetMapping("/data/{id}")
    @SaCheckPermission(value = "system:dict:query", type = StpSysUtil.TYPE)
    public R<DictDataVO> getData(@PathVariable Long id) {
        return R.ok(dictDataService.getData(id));
    }

    @PostMapping("/data")
    @SaCheckPermission(value = "system:dict:add", type = StpSysUtil.TYPE)
    public R<Long> createData(@Validated @RequestBody DictDataSaveRequest request) {
        return R.ok(dictDataService.createData(request));
    }

    @PutMapping("/data")
    @SaCheckPermission(value = "system:dict:edit", type = StpSysUtil.TYPE)
    public R<Void> updateData(@Validated @RequestBody DictDataSaveRequest request) {
        dictDataService.updateData(request);
        return R.ok();
    }

    @DeleteMapping("/data/{id}")
    @SaCheckPermission(value = "system:dict:remove", type = StpSysUtil.TYPE)
    public R<Void> deleteData(@PathVariable Long id) {
        dictDataService.deleteData(id);
        return R.ok();
    }
}
