package com.pivotos.system.service;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.TenantInitRequest;
import com.pivotos.system.domain.dto.TenantQuery;
import com.pivotos.system.domain.dto.TenantSaveRequest;
import com.pivotos.system.domain.entity.SysTenant;
import com.pivotos.system.domain.vo.TenantInitVO;
import com.pivotos.system.domain.vo.TenantVO;

import java.util.Set;

/** 租户服务 */
public interface TenantService {

    /** 租户分页 */
    PageResult<TenantVO> pageTenants(TenantQuery query);

    /** 租户详情 */
    TenantVO getTenant(Long tenantId);

    /** 新增租户 */
    Long createTenant(TenantSaveRequest request);

    /** 修改租户（含状态切换） */
    void updateTenant(TenantSaveRequest request);

    /** 删除租户（存在绑定用户则拒绝） */
    void deleteTenant(Long tenantId);

    /** 初始化向导：建租户 → 配套餐 → 建管理员（单事务） */
    TenantInitVO initTenant(TenantInitRequest request);

    /** 登录接线：要求租户存在且可用（不存在/停用/过期抛异常） */
    SysTenant requireActiveTenant(Long tenantId);

    /**
     * 租户的套餐菜单集合（菜单过滤用）。
     * 返回 null 表示不限制（租户不存在/未配套餐/套餐未设菜单范围）。
     */
    Set<Long> listPackageMenuIds(Long tenantId);
}
