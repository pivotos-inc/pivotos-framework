package com.pivotos.system.service;

import com.pivotos.system.domain.dto.TenantPackageQuery;
import com.pivotos.system.domain.dto.TenantPackageSaveRequest;
import com.pivotos.system.domain.entity.SysTenantPackage;
import com.pivotos.system.domain.vo.TenantPackageVO;

import java.util.List;
import java.util.Set;

/** 租户套餐服务 */
public interface TenantPackageService {

    /** 套餐列表（按名称/状态过滤） */
    List<TenantPackageVO> listPackages(TenantPackageQuery query);

    /** 套餐详情 */
    TenantPackageVO getPackage(Long packageId);

    /** 新增套餐 */
    Long createPackage(TenantPackageSaveRequest request);

    /** 修改套餐 */
    void updatePackage(TenantPackageSaveRequest request);

    /** 删除套餐（已被租户使用则拒绝） */
    void deletePackage(Long packageId);

    /** 要求套餐存在且启用，否则抛异常（租户绑定/向导用） */
    SysTenantPackage requireEnabledPackage(Long packageId);

    /** 解析套餐菜单 ID 集合；空/NULL 返回 null 表示不限制 */
    Set<Long> parseMenuIds(SysTenantPackage tenantPackage);
}
