package ${packageName}.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import ${packageName}.domain.dto.${className}CreateRequest;
import ${packageName}.domain.dto.${className}UpdateRequest;
import ${packageName}.domain.dto.${className}QueryRequest;
import ${packageName}.domain.vo.${className}VO;
import ${packageName}.domain.entity.${className};
import ${packageName}.service.${className}Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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
    @GetMapping
    @SaCheckPermission(value = "${moduleName}:${businessName}:list", type = StpSysUtil.TYPE)
    public R<IPage<${className}VO>> selectPage(@Valid ${className}QueryRequest query) {
        IPage<${className}> page = query.toPage();
        return R.ok(${classVarName}Service.selectPage(page, query));
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
}
