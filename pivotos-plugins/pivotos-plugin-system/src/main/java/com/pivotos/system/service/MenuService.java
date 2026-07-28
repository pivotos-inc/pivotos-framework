package com.pivotos.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.system.domain.dto.MenuQuery;
import com.pivotos.system.domain.dto.MenuSaveRequest;
import com.pivotos.system.domain.entity.SysMenu;
import com.pivotos.system.domain.vo.MenuVO;
import com.pivotos.system.domain.vo.RouterVO;
import com.pivotos.system.domain.vo.WorkbenchItemVO;

import java.util.List;

/** 菜单服务 */
public interface MenuService extends IService<SysMenu> {

    /** 菜单树查询 */
    List<MenuVO> treeMenus(MenuQuery query);

    /** 查询菜单详情 */
    MenuVO getMenu(Long menuId);

    /** 新增菜单，返回菜单ID */
    Long createMenu(MenuSaveRequest request);

    /** 修改菜单 */
    void updateMenu(MenuSaveRequest request);

    /** 删除菜单（有子菜单或已授权角色则拒绝） */
    void deleteMenu(Long menuId);

    /** 查询用户权限标识集合（超管返回 ["*:*:*"]） */
    List<String> listPermsByUserId(Long userId);

    /** 查询用户动态路由（M/C 类型菜单树，超管全量；仅 PC 端可见菜单） */
    List<RouterVO> listRoutersByUserId(Long userId);

    /** 查询用户移动端工作台宫格（C 类型平铺，按 device 过滤，超管全量） */
    List<WorkbenchItemVO> listWorkbenchItems(Long userId, String device);
}
