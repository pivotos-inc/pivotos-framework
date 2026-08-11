package ${packageName}.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import ${packageName}.domain.dto.${className}CreateRequest;
import ${packageName}.domain.dto.${className}UpdateRequest;
import ${packageName}.domain.dto.${className}QueryRequest;
import ${packageName}.domain.vo.${className}VO;
import ${packageName}.service.${className}Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
<#if hasFk>
import java.util.Map;
</#if>

/**
 * ${functionName} - 控制器
 *
 * @author ${author}
 * @date ${datetime}
 */
@RestController
@RequestMapping("/${moduleName}/${businessName}")
@RequiredArgsConstructor
public class ${className}Controller {

    private final ${className}Service ${classVarName}Service;

    /** 分页查询${functionName} */
    @GetMapping("/page")
    @SaCheckPermission(value = "${moduleName}:${businessName}:list", type = StpSysUtil.TYPE)
    public R<PageResult<${className}VO>> selectPage(@Valid ${className}QueryRequest query) {
        return R.ok(${classVarName}Service.selectPage(query));
    }

    /** 查询${functionName}详情 */
    @GetMapping("/{id}")
    @SaCheckPermission(value = "${moduleName}:${businessName}:query", type = StpSysUtil.TYPE)
    public R<${className}VO> getInfo(@PathVariable Long id) {
        return R.ok(${classVarName}Service.selectById(id));
    }

    /** 新增${functionName} */
    @PostMapping
    @SaCheckPermission(value = "${moduleName}:${businessName}:add", type = StpSysUtil.TYPE)
    public R<Void> add(@Valid @RequestBody ${className}CreateRequest request) {
        ${classVarName}Service.create(request);
        return R.ok();
    }

    /** 更新${functionName} */
    @PutMapping
    @SaCheckPermission(value = "${moduleName}:${businessName}:edit", type = StpSysUtil.TYPE)
    public R<Void> edit(@Valid @RequestBody ${className}UpdateRequest request) {
        ${classVarName}Service.update(request);
        return R.ok();
    }

    /** 删除${functionName} */
    @DeleteMapping("/{ids}")
    @SaCheckPermission(value = "${moduleName}:${businessName}:remove", type = StpSysUtil.TYPE)
    public R<Void> remove(@PathVariable List<Long> ids) {
        ${classVarName}Service.delete(ids);
        return R.ok();
    }
<#if hasFk>

    /** 查询${functionName}关联下拉选项（S50 / 2.4-F2；沿 query 权限） */
    @GetMapping("/fk-options/{field}")
    @SaCheckPermission(value = "${moduleName}:${businessName}:query", type = StpSysUtil.TYPE)
    public R<List<Map<String, Object>>> fkOptions(@PathVariable String field) {
        return R.ok(${classVarName}Service.selectFkOptions(field));
    }
</#if>
}
