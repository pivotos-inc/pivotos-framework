package com.pivotos.system.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.auth.account.StpMindUtil;
import com.pivotos.starter.auth.support.AuthSessionHolder;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.domain.entity.SysSocialUser;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.vo.MindLoginVO;
import com.pivotos.system.service.MindAuthService;
import com.pivotos.system.service.SocialUserService;
import com.pivotos.system.service.UserService;
import com.pivotos.system.service.WechatMiniService;
import com.pivotos.system.service.WechatSession;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/** 枢磐·智域认证编排实现 */
@Service
@RequiredArgsConstructor
public class MindAuthServiceImpl implements MindAuthService {

    private static final Logger log = LoggerFactory.getLogger(MindAuthServiceImpl.class);

    private final WechatMiniService wechatMiniService;
    private final SocialUserService socialUserService;
    private final UserService userService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MindLoginVO login(String code) {
        WechatSession session = wechatMiniService.code2Session(WechatMiniService.MIND_APP, code);
        SysSocialUser binding = socialUserService.findByChannelOpenId(CHANNEL_PIVOTOS_MIND, session.openId());
        if (binding == null) {
            SysUser user = registerMindUser("微信用户", null);
            socialUserService.bind(user.getId(), CHANNEL_PIVOTOS_MIND, session.openId(), session.unionId());
            log.info("[PivotOS Mind] 新微信用户自动建档 userId={} openId={}", user.getId(), session.openId());
            return new MindLoginVO(doLogin(user), true);
        }
        SysUser user = requireEnabledUser(binding.getUserId());
        // unionId 后补
        if (session.unionId() != null && !Objects.equals(session.unionId(), binding.getUnionId())) {
            binding.setUnionId(session.unionId());
            socialUserService.updateById(binding);
        }
        return new MindLoginVO(doLogin(user), false);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MindLoginVO passwordLogin(String username, String password) {
        SysUser user = userService.getByUsername(username);
        boolean fresh = false;
        if (user == null) {
            // H5 开发调试：用户不存在则自动注册（生产环境建议关闭）
            user = registerMindUser(username, BCrypt.hashpw(password));
            fresh = true;
            log.info("[PivotOS Mind] 账密自动注册 userId={} username={}", user.getId(), username);
        } else {
            if (!BCrypt.checkpw(password, user.getPassword())) {
                throw new ServiceException(SystemErrorCode.LOGIN_FAILED);
            }
        }
        requireEnabledUser(user.getId());
        return new MindLoginVO(doLogin(user), fresh);
    }

    /** 注册个人端用户：无部门/岗位/角色，状态启用 */
    private SysUser registerMindUser(String nickname, String password) {
        SysUser user = new SysUser();
        user.setUsername(generateUsername());
        user.setNickname(nickname);
        user.setPassword(password != null ? password : BCrypt.hashpw(RandomUtil.randomString(32)));
        user.setStatus(CommonStatusEnum.ENABLED.getValue());
        userService.save(user);
        return user;
    }

    private String generateUsername() {
        // 生成唯一用户名：mind_ + 10 位随机字母数字
        String base = "mind_" + RandomUtil.randomString(10);
        if (userService.getByUsername(base) == null) {
            return base;
        }
        return generateUsername();
    }

    private SysUser requireEnabledUser(Long userId) {
        SysUser user = userService.getById(userId);
        if (user == null) {
            throw new ServiceException(SystemErrorCode.USER_NOT_FOUND);
        }
        if (!Objects.equals(CommonStatusEnum.ENABLED.getValue(), user.getStatus())) {
            throw new ServiceException(SystemErrorCode.USER_DISABLED);
        }
        return user;
    }

    /** mind-user 体系登录并写入会话 */
    private String doLogin(SysUser user) {
        String token = StpMindUtil.login(user.getId());
        AuthSessionHolder.saveLoginUser(StpMindUtil.STP,
                new LoginUser(user.getId(), user.getUsername(), StpMindUtil.TYPE, null));
        return token;
    }
}
