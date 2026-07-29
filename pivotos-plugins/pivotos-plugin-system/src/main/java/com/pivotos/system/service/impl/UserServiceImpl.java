package com.pivotos.system.service.impl;

import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.constant.CommonConstants;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.constant.SystemConstants;
import com.pivotos.system.convert.UserConvert;
import com.pivotos.system.domain.dto.ResetPasswordBody;
import com.pivotos.system.domain.dto.ChangePasswordBody;
import com.pivotos.system.domain.dto.ProfileUpdateRequest;
import com.pivotos.system.domain.dto.UserQuery;
import com.pivotos.system.domain.dto.UserSaveRequest;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.entity.SysUserRole;
import com.pivotos.system.domain.vo.UserVO;
import com.pivotos.system.mapper.SysUserMapper;
import com.pivotos.system.mapper.SysUserRoleMapper;
import com.pivotos.system.service.ConfigService;
import com.pivotos.system.service.UserService;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/** 用户服务实现 */
@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<SysUserMapper, SysUser> implements UserService {

    private final UserConvert userConvert;
    private final SysUserRoleMapper userRoleMapper;
    private final ConfigService configService;

    @Override
    public PageResult<UserVO> pageUsers(UserQuery query) {
        LambdaQueryWrapper<SysUser> wrapper = Wrappers.<SysUser>lambdaQuery()
                .like(StringUtils.hasText(query.getUsername()), SysUser::getUsername, query.getUsername())
                .like(StringUtils.hasText(query.getNickname()), SysUser::getNickname, query.getNickname())
                .like(StringUtils.hasText(query.getMobile()), SysUser::getMobile, query.getMobile())
                .eq(query.getDeptId() != null, SysUser::getDeptId, query.getDeptId())
                .eq(query.getStatus() != null, SysUser::getStatus, query.getStatus())
                .orderByDesc(SysUser::getCreateTime);
        Page<SysUser> page = page(PageUtils.toMpPage(query), wrapper);
        return PageUtils.toPageResult(page, userConvert.toVoList(page.getRecords()));
    }

    @Override
    public UserVO getUser(Long userId) {
        return userConvert.toVo(requireUser(userId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createUser(UserSaveRequest request) {
        checkUsernameUnique(request.getUsername(), null);
        SysUser entity = userConvert.toEntity(request);
        entity.setId(null);
        String rawPassword = StringUtils.hasText(request.getPassword())
                ? request.getPassword()
                : configService.getConfigValue(SystemConstants.CONFIG_INIT_PASSWORD,
                        SystemConstants.DEFAULT_INIT_PASSWORD);
        entity.setPassword(BCrypt.hashpw(rawPassword, BCrypt.gensalt()));
        save(entity);
        rebuildUserRoles(entity.getId(), request.getRoleIds());
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUser(UserSaveRequest request) {
        SysUser exist = requireUser(request.getId());
        checkSuperAdminStatusChange(exist, request);
        checkUsernameUnique(request.getUsername(), request.getId());
        SysUser entity = userConvert.toEntity(request);
        // 密码留空表示不变（updateById 忽略 null 字段）
        entity.setPassword(StringUtils.hasText(request.getPassword())
                ? BCrypt.hashpw(request.getPassword(), BCrypt.gensalt())
                : null);
        updateById(entity);
        rebuildUserRoles(request.getId(), request.getRoleIds());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteUser(Long userId) {
        requireUser(userId);
        if (CommonConstants.SUPER_ADMIN_ID.equals(userId)) {
            throw new ServiceException(SystemErrorCode.SUPER_ADMIN_FORBIDDEN);
        }
        removeById(userId);
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
    }

    @Override
    public void resetPassword(ResetPasswordBody body) {
        requireUser(body.getUserId());
        SysUser entity = new SysUser();
        entity.setId(body.getUserId());
        entity.setPassword(BCrypt.hashpw(body.getPassword(), BCrypt.gensalt()));
        updateById(entity);
    }

    @Override
    public SysUser getByUsername(String username) {
        return getOne(Wrappers.<SysUser>lambdaQuery().eq(SysUser::getUsername, username));
    }

    @Override
    public void updateProfile(Long userId, ProfileUpdateRequest request) {
        requireUser(userId);
        SysUser entity = new SysUser();
        entity.setId(userId);
        entity.setNickname(request.getNickname());
        entity.setAvatar(request.getAvatar());
        entity.setEmail(request.getEmail());
        entity.setMobile(request.getMobile());
        // MP updateById 只更新非 null 字段，null 即"不动该列"
        updateById(entity);
    }

    @Override
    public void changePassword(Long userId, ChangePasswordBody body) {
        SysUser user = requireUser(userId);
        if (!BCrypt.checkpw(body.getOldPassword(), user.getPassword())) {
            throw new ServiceException(SystemErrorCode.OLD_PASSWORD_ERROR);
        }
        SysUser entity = new SysUser();
        entity.setId(userId);
        entity.setPassword(BCrypt.hashpw(body.getNewPassword(), BCrypt.gensalt()));
        updateById(entity);
    }

    /** 查询用户，不存在抛 2004 */
    private SysUser requireUser(Long userId) {
        SysUser user = getById(userId);
        if (user == null) {
            throw new ServiceException(SystemErrorCode.USER_NOT_FOUND);
        }
        return user;
    }

    /** 用户名唯一性校验（排除自身） */
    private void checkUsernameUnique(String username, Long excludeId) {
        long count = count(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)
                .ne(excludeId != null, SysUser::getId, excludeId));
        if (count > 0) {
            throw new ServiceException(SystemErrorCode.USERNAME_EXISTS);
        }
    }

    /** 超级管理员不允许被停用 */
    private void checkSuperAdminStatusChange(SysUser exist, UserSaveRequest request) {
        if (CommonConstants.SUPER_ADMIN_ID.equals(exist.getId())
                && request.getStatus() != null
                && !Objects.equals(request.getStatus(), exist.getStatus())) {
            throw new ServiceException(SystemErrorCode.SUPER_ADMIN_FORBIDDEN);
        }
    }

    /** 重建用户-角色关联（先删后插） */
    private void rebuildUserRoles(Long userId, List<Long> roleIds) {
        userRoleMapper.delete(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId));
        if (roleIds != null && !roleIds.isEmpty()) {
            roleIds.forEach(roleId -> userRoleMapper.insert(new SysUserRole(userId, roleId)));
        }
    }
}
