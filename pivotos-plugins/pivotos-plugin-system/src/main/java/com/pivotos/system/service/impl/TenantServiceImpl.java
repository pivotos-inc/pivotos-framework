package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.convert.TenantConvert;
import com.pivotos.system.domain.dto.TenantInitRequest;
import com.pivotos.system.domain.dto.TenantQuery;
import com.pivotos.system.domain.dto.TenantSaveRequest;
import com.pivotos.system.domain.dto.UserSaveRequest;
import com.pivotos.system.domain.entity.SysTenant;
import com.pivotos.system.domain.entity.SysTenantPackage;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.vo.TenantInitVO;
import com.pivotos.system.domain.vo.TenantVO;
import com.pivotos.system.mapper.SysTenantMapper;
import com.pivotos.system.mapper.SysTenantPackageMapper;
import com.pivotos.system.mapper.SysUserMapper;
import com.pivotos.system.service.TenantPackageService;
import com.pivotos.system.service.TenantService;
import com.pivotos.system.service.UserService;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 租户服务实现 */
@Service
@RequiredArgsConstructor
public class TenantServiceImpl extends ServiceImpl<SysTenantMapper, SysTenant> implements TenantService {

    private final TenantConvert tenantConvert;
    private final TenantPackageService tenantPackageService;
    private final SysTenantPackageMapper tenantPackageMapper;
    private final SysUserMapper userMapper;
    /** 向导建管理员复用用户创建链路（用户名唯一校验 + BCrypt + 角色重建）；@Lazy 防潜在装配环 */
    private final @Lazy UserService userService;

    @Override
    public PageResult<TenantVO> pageTenants(TenantQuery query) {
        LambdaQueryWrapper<SysTenant> wrapper = Wrappers.<SysTenant>lambdaQuery()
                .like(StringUtils.hasText(query.getTenantCode()), SysTenant::getTenantCode, query.getTenantCode())
                .like(StringUtils.hasText(query.getTenantName()), SysTenant::getTenantName, query.getTenantName())
                .eq(query.getPackageId() != null, SysTenant::getPackageId, query.getPackageId())
                .eq(query.getStatus() != null, SysTenant::getStatus, query.getStatus())
                .orderByDesc(SysTenant::getCreateTime);
        Page<SysTenant> page = page(PageUtils.toMpPage(query), wrapper);
        List<TenantVO> vos = tenantConvert.toVoList(page.getRecords());
        enrich(vos);
        return PageUtils.toPageResult(page, vos);
    }

    @Override
    public TenantVO getTenant(Long tenantId) {
        TenantVO vo = tenantConvert.toVo(requireTenant(tenantId));
        enrich(List.of(vo));
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createTenant(TenantSaveRequest request) {
        checkTenantCodeUnique(request.getTenantCode(), null);
        requireEnabledPackageIfPresent(request.getPackageId());
        SysTenant entity = tenantConvert.toEntity(request);
        entity.setId(null);
        entity.setAccountLimit(request.getAccountLimit() != null ? request.getAccountLimit() : 0);
        entity.setStatus(request.getStatus() != null
                ? request.getStatus() : CommonStatusEnum.ENABLED.getValue());
        save(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTenant(TenantSaveRequest request) {
        SysTenant exist = requireTenant(request.getId());
        if (!exist.getTenantCode().equals(request.getTenantCode())) {
            checkTenantCodeUnique(request.getTenantCode(), request.getId());
        }
        requireEnabledPackageIfPresent(request.getPackageId());
        // 全量替换语义：MP updateById 忽略 null 字段，可空列（套餐/过期时间/备注）清不回 NULL，
        // 改用 UpdateWrapper 显式 set 全部业务列；wrapper 不走审计填充，updateBy/updateTime 手工补齐
        update(Wrappers.<SysTenant>lambdaUpdate()
                .eq(SysTenant::getId, request.getId())
                .set(SysTenant::getTenantCode, request.getTenantCode())
                .set(SysTenant::getTenantName, request.getTenantName())
                .set(SysTenant::getPackageId, request.getPackageId())
                .set(SysTenant::getExpireTime, request.getExpireTime())
                .set(SysTenant::getRemark, request.getRemark())
                .set(SysTenant::getAccountLimit,
                        request.getAccountLimit() != null ? request.getAccountLimit() : exist.getAccountLimit())
                .set(SysTenant::getStatus,
                        request.getStatus() != null ? request.getStatus() : exist.getStatus())
                .set(SysTenant::getUpdateBy, LoginContext.getUserId())
                .set(SysTenant::getUpdateTime, LocalDateTime.now()));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTenant(Long tenantId) {
        requireTenant(tenantId);
        long users = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getTenantId, tenantId));
        if (users > 0) {
            throw new ServiceException(SystemErrorCode.TENANT_HAS_USERS);
        }
        removeById(tenantId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TenantInitVO initTenant(TenantInitRequest request) {
        // 第一步：建租户（编码唯一 + 套餐必须存在且启用）
        checkTenantCodeUnique(request.getTenantCode(), null);
        SysTenantPackage tenantPackage = tenantPackageService.requireEnabledPackage(request.getPackageId());
        int limit = request.getAccountLimit() != null ? request.getAccountLimit() : 0;
        SysTenant tenant = new SysTenant();
        tenant.setTenantCode(request.getTenantCode());
        tenant.setTenantName(request.getTenantName());
        tenant.setPackageId(tenantPackage.getId());
        tenant.setAccountLimit(limit);
        tenant.setExpireTime(request.getExpireTime());
        tenant.setStatus(CommonStatusEnum.ENABLED.getValue());
        tenant.setRemark(request.getRemark());
        save(tenant);

        // 第二步：账号数上限校验（含本步要建的管理员）
        long current = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getTenantId, tenant.getId()));
        if (limit > 0 && current + 1 > limit) {
            throw new ServiceException(SystemErrorCode.TENANT_ACCOUNT_LIMIT);
        }

        // 第三步：建管理员（复用用户创建链路，绑定 tenant_id）
        UserSaveRequest admin = new UserSaveRequest();
        admin.setUsername(request.getAdminUsername());
        admin.setNickname(request.getAdminNickname());
        admin.setPassword(request.getAdminPassword());
        admin.setStatus(CommonStatusEnum.ENABLED.getValue());
        admin.setRoleIds(request.getAdminRoleIds());
        admin.setTenantId(tenant.getId());
        Long adminUserId = userService.createUser(admin);
        return new TenantInitVO(tenant.getId(), adminUserId);
    }

    @Override
    public SysTenant requireActiveTenant(Long tenantId) {
        SysTenant tenant = getById(tenantId);
        if (tenant == null) {
            throw new ServiceException(SystemErrorCode.TENANT_NOT_FOUND);
        }
        if (!Objects.equals(CommonStatusEnum.ENABLED.getValue(), tenant.getStatus())) {
            throw new ServiceException(SystemErrorCode.TENANT_DISABLED);
        }
        if (tenant.getExpireTime() != null && tenant.getExpireTime().isBefore(LocalDateTime.now())) {
            throw new ServiceException(SystemErrorCode.TENANT_EXPIRED);
        }
        return tenant;
    }

    @Override
    public Set<Long> listPackageMenuIds(Long tenantId) {
        SysTenant tenant = getById(tenantId);
        if (tenant == null || tenant.getPackageId() == null) {
            return null;
        }
        return tenantPackageService.parseMenuIds(
                tenantPackageMapper.selectById(tenant.getPackageId()));
    }

    /** 填充 VO 扩展字段：套餐名称 + 已建账号数 */
    private void enrich(List<TenantVO> vos) {
        if (vos.isEmpty()) {
            return;
        }
        Set<Long> packageIds = vos.stream().map(TenantVO::getPackageId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SysTenantPackage> packageMap = packageIds.isEmpty()
                ? Map.of()
                : tenantPackageMapper.selectBatchIds(packageIds).stream()
                        .collect(Collectors.toMap(SysTenantPackage::getId, Function.identity()));
        vos.forEach(vo -> {
            // 判空后再 get：packageIds 为空时 packageMap 是不可变 Map.of()，get(null) 会抛 NPE
            SysTenantPackage pkg = vo.getPackageId() != null ? packageMap.get(vo.getPackageId()) : null;
            vo.setPackageName(pkg != null ? pkg.getPackageName() : null);
            vo.setAccountCount(userMapper.selectCount(
                    Wrappers.<SysUser>lambdaQuery().eq(SysUser::getTenantId, vo.getId())));
        });
    }

    /** 套餐非空时必须存在且启用 */
    private void requireEnabledPackageIfPresent(Long packageId) {
        if (packageId != null) {
            tenantPackageService.requireEnabledPackage(packageId);
        }
    }

    /** 获取租户，不存在则抛异常 */
    private SysTenant requireTenant(Long tenantId) {
        SysTenant tenant = getById(tenantId);
        if (tenant == null) {
            throw new ServiceException(SystemErrorCode.TENANT_NOT_FOUND);
        }
        return tenant;
    }

    /** 校验租户编码唯一（应用层口径，不设 DB 唯一键——踩坑 24 逻辑删除复活撞键） */
    private void checkTenantCodeUnique(String tenantCode, Long excludeId) {
        Long count = lambdaQuery()
                .eq(SysTenant::getTenantCode, tenantCode)
                .ne(excludeId != null, SysTenant::getId, excludeId)
                .count();
        if (count > 0) {
            throw new ServiceException(SystemErrorCode.TENANT_CODE_EXISTS);
        }
    }
}
