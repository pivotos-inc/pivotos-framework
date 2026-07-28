package com.pivotos.system.controller;

import com.pivotos.common.core.enums.error.GlobalErrorCode;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.system.domain.vo.WorkbenchItemVO;
import com.pivotos.system.service.MenuService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 移动端工作台接口（sys / app / wx-mini 三账号体系通用，
 * 登录态由 LoginContextFilter 统一解析）。
 */
@RestController
@RequestMapping("/app/system")
@RequiredArgsConstructor
public class WorkbenchController {

    private final MenuService menuService;

    /**
     * 工作台宫格：按角色可见性 + device 端过滤返回 C 类型菜单
     *
     * @param device 端标识（app / mini），默认 app
     */
    @GetMapping("/workbench")
    public R<List<WorkbenchItemVO>> workbench(@RequestParam(defaultValue = "app") String device) {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            throw new ServiceException(GlobalErrorCode.UNAUTHORIZED);
        }
        return R.ok(menuService.listWorkbenchItems(userId, device));
    }
}
