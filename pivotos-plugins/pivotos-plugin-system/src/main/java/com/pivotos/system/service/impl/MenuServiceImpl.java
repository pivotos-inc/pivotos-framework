package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.constant.CommonConstants;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.constant.SystemConstants;
import com.pivotos.system.convert.MenuConvert;
import com.pivotos.system.domain.dto.MenuQuery;
import com.pivotos.system.domain.dto.MenuSaveRequest;
import com.pivotos.system.domain.entity.SysMenu;
import com.pivotos.system.domain.entity.SysRoleMenu;
import com.pivotos.system.domain.entity.SysUserRole;
import com.pivotos.system.domain.vo.MenuVO;
import com.pivotos.system.domain.vo.RouterVO;
import com.pivotos.system.mapper.SysMenuMapper;
import com.pivotos.system.mapper.SysRoleMenuMapper;
import com.pivotos.system.mapper.SysUserRoleMapper;
import com.pivotos.system.service.MenuService;
import com.pivotos.system.service.RoleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 菜单服务实现 */
@Service
@RequiredArgsConstructor
public class MenuServiceImpl extends ServiceImpl<SysMenuMapper, SysMenu> implements MenuService {

    private static final String TYPE_DIR = "M";
    private static final String TYPE_MENU = "C";
    private static final String TYPE_BUTTON = "F";

    private final MenuConvert menuConvert;
    private final SysRoleMenuMapper roleMenuMapper;
    private final SysUserRoleMapper userRoleMapper;
    private final RoleService roleService;

    @Override
    public List<MenuVO> treeMenus(MenuQuery query) {
        List<SysMenu> menus = list(Wrappers.<SysMenu>lambdaQuery()
                .like(StringUtils.hasText(query.getMenuName()), SysMenu::getMenuName, query.getMenuName())
                .eq(query.getStatus() != null, SysMenu::getStatus, query.getStatus())
                .orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getSort));
        return buildMenuTree(menuConvert.toVoList(menus));
    }

    @Override
    public MenuVO getMenu(Long menuId) {
        return menuConvert.toVo(requireMenu(menuId));
    }

    @Override
    public Long createMenu(MenuSaveRequest request) {
        checkParent(request.getParentId());
        SysMenu entity = menuConvert.toEntity(request);
        entity.setId(null);
        save(entity);
        return entity.getId();
    }

    @Override
    public void updateMenu(MenuSaveRequest request) {
        requireMenu(request.getId());
        if (Objects.equals(request.getId(), request.getParentId())) {
            throw new ServiceException(SystemErrorCode.MENU_NOT_FOUND.getCode(), "父菜单不能选择自己");
        }
        checkParent(request.getParentId());
        updateById(menuConvert.toEntity(request));
    }

    @Override
    public void deleteMenu(Long menuId) {
        requireMenu(menuId);
        long children = count(Wrappers.<SysMenu>lambdaQuery().eq(SysMenu::getParentId, menuId));
        if (children > 0) {
            throw new ServiceException(SystemErrorCode.MENU_HAS_CHILDREN);
        }
        long assigned = roleMenuMapper.selectCount(
                Wrappers.<SysRoleMenu>lambdaQuery().eq(SysRoleMenu::getMenuId, menuId));
        if (assigned > 0) {
            throw new ServiceException(SystemErrorCode.MENU_ASSIGNED);
        }
        removeById(menuId);
    }

    @Override
    public List<String> listPermsByUserId(Long userId) {
        if (roleService.isSuperAdmin(userId)) {
            return List.of(SystemConstants.SUPER_ADMIN_PERM);
        }
        List<Long> menuIds = listMenuIdsByUserId(userId);
        if (menuIds.isEmpty()) {
            return List.of();
        }
        return list(Wrappers.<SysMenu>lambdaQuery()
                        .in(SysMenu::getId, menuIds)
                        .eq(SysMenu::getStatus, CommonStatusEnum.ENABLED.getValue())
                        .ne(SysMenu::getPerms, ""))
                .stream().map(SysMenu::getPerms).distinct().toList();
    }

    @Override
    public List<RouterVO> listRoutersByUserId(Long userId) {
        List<SysMenu> menus;
        if (roleService.isSuperAdmin(userId)) {
            menus = list(routeWrapper(null));
        } else {
            List<Long> menuIds = listMenuIdsByUserId(userId);
            if (menuIds.isEmpty()) {
                return List.of();
            }
            menus = list(routeWrapper(menuIds));
        }
        return buildRouterTree(menus);
    }

    /** 用户经 角色→菜单 链路可见的菜单ID集合 */
    private List<Long> listMenuIdsByUserId(Long userId) {
        List<Long> roleIds = userRoleMapper.selectList(Wrappers.<SysUserRole>lambdaQuery()
                        .eq(SysUserRole::getUserId, userId))
                .stream().map(SysUserRole::getRoleId).toList();
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return roleMenuMapper.selectList(Wrappers.<SysRoleMenu>lambdaQuery()
                        .in(SysRoleMenu::getRoleId, roleIds))
                .stream().map(SysRoleMenu::getMenuId).distinct().toList();
    }

    /** 路由查询条件：M/C 类型、正常状态、按父ID与排序 */
    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<SysMenu> routeWrapper(List<Long> menuIds) {
        return Wrappers.<SysMenu>lambdaQuery()
                .in(menuIds != null, SysMenu::getId, menuIds)
                .in(SysMenu::getMenuType, TYPE_DIR, TYPE_MENU)
                .eq(SysMenu::getStatus, CommonStatusEnum.ENABLED.getValue())
                .orderByAsc(SysMenu::getParentId)
                .orderByAsc(SysMenu::getSort);
    }

    /** 菜单实体树 → RouterVO 树 */
    private List<RouterVO> buildRouterTree(List<SysMenu> menus) {
        Map<Long, SysMenu> byId = menus.stream()
                .collect(Collectors.toMap(SysMenu::getId, Function.identity()));
        List<RouterVO> roots = new ArrayList<>();
        for (SysMenu menu : menus) {
            if (CommonConstants.TREE_ROOT_ID.equals(menu.getParentId()) || !byId.containsKey(menu.getParentId())) {
                roots.add(toRouter(menu, byId));
            }
        }
        return roots;
    }

    private RouterVO toRouter(SysMenu menu, Map<Long, SysMenu> byId) {
        RouterVO router = new RouterVO();
        router.setPath(menu.getPath());
        router.setName(toRouteName(menu.getPath()));
        router.setComponent(TYPE_DIR.equals(menu.getMenuType()) ? "Layout" : menu.getComponent());
        router.setHidden(Objects.equals(CommonStatusEnum.DISABLED.getValue(), menu.getVisible()));
        RouterVO.Meta meta = new RouterVO.Meta();
        meta.setTitle(menu.getMenuName());
        meta.setIcon(menu.getIcon());
        router.setMeta(meta);
        List<RouterVO> children = byId.values().stream()
                .filter(m -> Objects.equals(m.getParentId(), menu.getId()))
                .sorted(Comparator.comparing(SysMenu::getSort, Comparator.nullsLast(Integer::compareTo)))
                .map(m -> toRouter(m, byId))
                .toList();
        if (!children.isEmpty()) {
            router.setChildren(children);
        }
        return router;
    }

    /** 路由名：路径转大驼峰（如 user → User，/system → System） */
    private String toRouteName(String path) {
        if (!StringUtils.hasText(path)) {
            return "";
        }
        String clean = path.startsWith("/") ? path.substring(1) : path;
        return StringUtils.capitalize(clean.replace("/", "_"));
    }

    /** 平铺菜单 VO → 树 */
    private List<MenuVO> buildMenuTree(List<MenuVO> flat) {
        Map<Long, MenuVO> byId = flat.stream()
                .collect(Collectors.toMap(MenuVO::getId, Function.identity()));
        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO vo : flat) {
            MenuVO parent = byId.get(vo.getParentId());
            if (parent == null) {
                roots.add(vo);
            } else {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(vo);
            }
        }
        return roots;
    }

    private SysMenu requireMenu(Long menuId) {
        SysMenu menu = getById(menuId);
        if (menu == null) {
            throw new ServiceException(SystemErrorCode.MENU_NOT_FOUND);
        }
        return menu;
    }

    /** 父菜单校验：根（0）放行；否则必须存在且不是按钮 */
    private void checkParent(Long parentId) {
        if (CommonConstants.TREE_ROOT_ID.equals(parentId)) {
            return;
        }
        SysMenu parent = getById(parentId);
        if (parent == null) {
            throw new ServiceException(SystemErrorCode.MENU_NOT_FOUND.getCode(), "父菜单不存在");
        }
        if (TYPE_BUTTON.equals(parent.getMenuType())) {
            throw new ServiceException(SystemErrorCode.MENU_NOT_FOUND.getCode(), "按钮类型下不允许挂子节点");
        }
    }
}
