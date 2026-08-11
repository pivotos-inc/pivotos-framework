package ${packageName}.controller;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpMobileUtil;
import ${packageName}.domain.dto.${className}CreateRequest;
import ${packageName}.domain.dto.${className}UpdateRequest;
import ${packageName}.domain.dto.${className}QueryRequest;
import ${packageName}.domain.vo.${className}VO;
import ${packageName}.service.${className}Service;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * ${functionName} - 移动端控制器（App/H5/小程序）
 *
 * <p>S47（2.3-F3 H5 多账号数据联通）：与 ${className}Controller 同一 Service 的
 * 全量 CRUD 镜像，端点挂 /app 前缀。鉴权为「app-user / wx-mini-user 任一体系登录即可」
 * （Sa-Token 注解 type 是单值，OR 语义由 StpMobileUtil 程序化校验实现）；
 * 不发放权限串，移动端入口可见性由工作台宫格「角色可见性」既有机制控制，
 * PC 管理端 sys 体系端点与权限模型零扰动。
 *
 * @author ${author}
 * @date ${datetime}
 */
@RestController
@RequestMapping("/app/${moduleName}/${businessName}")
@RequiredArgsConstructor
public class ${className}AppController {

    private final ${className}Service ${classVarName}Service;

    /** 分页查询${functionName}（移动端） */
    @GetMapping("/page")
    public R<PageResult<${className}VO>> selectPage(@Valid ${className}QueryRequest query) {
        StpMobileUtil.checkLogin();
        return R.ok(${classVarName}Service.selectPage(query));
    }

    /** 查询${functionName}详情（移动端） */
    @GetMapping("/{id}")
    public R<${className}VO> getInfo(@PathVariable Long id) {
        StpMobileUtil.checkLogin();
        return R.ok(${classVarName}Service.selectById(id));
    }

    /** 新增${functionName}（移动端） */
    @PostMapping
    public R<Void> add(@Valid @RequestBody ${className}CreateRequest request) {
        StpMobileUtil.checkLogin();
        ${classVarName}Service.create(request);
        return R.ok();
    }

    /** 更新${functionName}（移动端） */
    @PutMapping
    public R<Void> edit(@Valid @RequestBody ${className}UpdateRequest request) {
        StpMobileUtil.checkLogin();
        ${classVarName}Service.update(request);
        return R.ok();
    }

    /** 删除${functionName}（移动端） */
    @DeleteMapping("/{ids}")
    public R<Void> remove(@PathVariable List<Long> ids) {
        StpMobileUtil.checkLogin();
        ${classVarName}Service.delete(ids);
        return R.ok();
    }
}
