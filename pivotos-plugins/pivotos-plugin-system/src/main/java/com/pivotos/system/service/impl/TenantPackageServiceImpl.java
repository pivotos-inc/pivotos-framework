package com.pivotos.system.service.impl;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.convert.TenantPackageConvert;
import com.pivotos.system.domain.dto.TenantPackageQuery;
import com.pivotos.system.domain.dto.TenantPackageSaveRequest;
import com.pivotos.system.domain.entity.SysTenant;
import com.pivotos.system.domain.entity.SysTenantPackage;
import com.pivotos.system.domain.vo.TenantPackageVO;
import com.pivotos.system.mapper.SysTenantMapper;
import com.pivotos.system.mapper.SysTenantPackageMapper;
import com.pivotos.system.service.TenantPackageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** 租户套餐服务实现 */
@Service
@RequiredArgsConstructor
public class TenantPackageServiceImpl extends ServiceImpl<SysTenantPackageMapper, SysTenantPackage>
        implements TenantPackageService {

    private final TenantPackageConvert tenantPackageConvert;
    private final SysTenantMapper tenantMapper;

    @Override
    public List<TenantPackageVO> listPackages(TenantPackageQuery query) {
        List<SysTenantPackage> packages = list(Wrappers.<SysTenantPackage>lambdaQuery()
                .like(StringUtils.hasText(query.getPackageName()), SysTenantPackage::getPackageName,
                        query.getPackageName())
                .eq(query.getStatus() != null, SysTenantPackage::getStatus, query.getStatus())
                .orderByDesc(SysTenantPackage::getCreateTime));
        List<TenantPackageVO> vos = tenantPackageConvert.toVoList(packages);
        Map<Long, SysTenantPackage> byId = packages.stream()
                .collect(Collectors.toMap(SysTenantPackage::getId, p -> p));
        vos.forEach(vo -> {
            vo.setMenuIds(parseMenuIdList(byId.get(vo.getId())));
            vo.setTenantCount(tenantMapper.selectCount(
                    Wrappers.<SysTenant>lambdaQuery().eq(SysTenant::getPackageId, vo.getId())));
        });
        return vos;
    }

    @Override
    public TenantPackageVO getPackage(Long packageId) {
        SysTenantPackage entity = requirePackage(packageId);
        TenantPackageVO vo = tenantPackageConvert.toVo(entity);
        vo.setMenuIds(parseMenuIdList(entity));
        vo.setTenantCount(tenantMapper.selectCount(
                Wrappers.<SysTenant>lambdaQuery().eq(SysTenant::getPackageId, packageId)));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPackage(TenantPackageSaveRequest request) {
        SysTenantPackage entity = tenantPackageConvert.toEntity(request);
        entity.setId(null);
        entity.setMenuIds(serializeMenuIds(request.getMenuIds()));
        entity.setStatus(request.getStatus() != null
                ? request.getStatus() : CommonStatusEnum.ENABLED.getValue());
        save(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePackage(TenantPackageSaveRequest request) {
        SysTenantPackage exist = requirePackage(request.getId());
        // 全量替换语义：同租户修改，UpdateWrapper 显式 set（menuIds 可清回 NULL 表示不限制）；
        // wrapper 不走审计填充，updateBy/updateTime 手工补齐
        update(Wrappers.<SysTenantPackage>lambdaUpdate()
                .eq(SysTenantPackage::getId, request.getId())
                .set(SysTenantPackage::getPackageName, request.getPackageName())
                .set(SysTenantPackage::getMenuIds, serializeMenuIds(request.getMenuIds()))
                .set(SysTenantPackage::getStatus,
                        request.getStatus() != null ? request.getStatus() : exist.getStatus())
                .set(SysTenantPackage::getRemark, request.getRemark())
                .set(SysTenantPackage::getUpdateBy, LoginContext.getUserId())
                .set(SysTenantPackage::getUpdateTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePackage(Long packageId) {
        requirePackage(packageId);
        long tenants = tenantMapper.selectCount(
                Wrappers.<SysTenant>lambdaQuery().eq(SysTenant::getPackageId, packageId));
        if (tenants > 0) {
            throw new ServiceException(SystemErrorCode.TENANT_PACKAGE_IN_USE);
        }
        removeById(packageId);
    }

    @Override
    public SysTenantPackage requireEnabledPackage(Long packageId) {
        SysTenantPackage entity = requirePackage(packageId);
        if (!Objects.equals(CommonStatusEnum.ENABLED.getValue(), entity.getStatus())) {
            throw new ServiceException(SystemErrorCode.TENANT_PACKAGE_DISABLED);
        }
        return entity;
    }

    @Override
    public Set<Long> parseMenuIds(SysTenantPackage tenantPackage) {
        List<Long> ids = parseMenuIdList(tenantPackage);
        return ids == null ? null : new HashSet<>(ids);
    }

    /** 解析套餐菜单 ID 列表（保持 JSON 数组顺序，VO 用）；空/NULL 返回 null 表示不限制 */
    private List<Long> parseMenuIdList(SysTenantPackage tenantPackage) {
        if (tenantPackage == null || !StringUtils.hasText(tenantPackage.getMenuIds())) {
            return null;
        }
        List<Long> ids = JSON.parseArray(tenantPackage.getMenuIds(), Long.class);
        return CollectionUtils.isEmpty(ids) ? null : ids;
    }

    /** 获取套餐，不存在则抛异常 */
    private SysTenantPackage requirePackage(Long packageId) {
        SysTenantPackage entity = getById(packageId);
        if (entity == null) {
            throw new ServiceException(SystemErrorCode.TENANT_PACKAGE_NOT_FOUND);
        }
        return entity;
    }

    /** 菜单 ID 集合 → JSON 存储；空集合存 NULL（语义=不限制） */
    private String serializeMenuIds(List<Long> menuIds) {
        return CollectionUtils.isEmpty(menuIds) ? null : JSON.toJSONString(menuIds);
    }
}
