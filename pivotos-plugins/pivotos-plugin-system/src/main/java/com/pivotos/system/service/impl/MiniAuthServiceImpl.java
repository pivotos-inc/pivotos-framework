package com.pivotos.system.service.impl;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.auth.account.StpWxMiniUtil;
import com.pivotos.starter.auth.support.AuthSessionHolder;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.domain.entity.SysSocialUser;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.vo.MiniLoginVO;
import com.pivotos.system.service.MiniAuthService;
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

/** 微信小程序登录编排实现 */
@Service
@RequiredArgsConstructor
public class MiniAuthServiceImpl implements MiniAuthService {

    private static final Logger log = LoggerFactory.getLogger(MiniAuthServiceImpl.class);

    private final WechatMiniService wechatMiniService;
    private final SocialUserService socialUserService;
    private final UserService userService;

    @Override
    public MiniLoginVO login(String code) {
        WechatSession session = wechatMiniService.code2Session(code);
        SysSocialUser binding = socialUserService.findByChannelOpenId(CHANNEL_WECHAT_MINI, session.openId());
        if (binding == null) {
            return new MiniLoginVO(null, false);
        }
        SysUser user = requireEnabledUser(binding.getUserId());
        // unionId 后补：微信有时首登不下发 unionid，登录时遇到就补齐，利于跨端打通核查
        if (session.unionId() != null && !Objects.equals(session.unionId(), binding.getUnionId())) {
            binding.setUnionId(session.unionId());
            socialUserService.updateById(binding);
        }
        return new MiniLoginVO(doLogin(user), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniLoginVO phoneLogin(String loginCode, String phoneCode) {
        WechatSession session = wechatMiniService.code2Session(loginCode);
        String phone = wechatMiniService.getPhoneNumber(phoneCode);
        SysUser user = userService.getOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getMobile, phone)
                .last("LIMIT 1"));
        if (user == null) {
            user = registerByPhone(phone);
        }
        requireEnabledUser(user.getId());
        bindOnce(user.getId(), session);
        return new MiniLoginVO(doLogin(user), true);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public MiniLoginVO bindAccount(String loginCode, String username, String password) {
        WechatSession session = wechatMiniService.code2Session(loginCode);
        SysUser user = userService.getByUsername(username);
        // 与账密登录同纪律：不存在与密码错误统一 2001
        if (user == null || !BCrypt.checkpw(password, user.getPassword())) {
            throw new ServiceException(SystemErrorCode.LOGIN_FAILED);
        }
        requireEnabledUser(user.getId());
        bindOnce(user.getId(), session);
        return new MiniLoginVO(doLogin(user), true);
    }

    /** 已绑定则直接复用（幂等），未绑定则建立绑定 */
    private void bindOnce(Long userId, WechatSession session) {
        SysSocialUser existing = socialUserService.findByChannelOpenId(CHANNEL_WECHAT_MINI, session.openId());
        if (existing != null) {
            if (!Objects.equals(existing.getUserId(), userId)) {
                throw new ServiceException(SystemErrorCode.SOCIAL_ALREADY_BOUND);
            }
            return;
        }
        socialUserService.bind(userId, CHANNEL_WECHAT_MINI, session.openId(), session.unionId());
    }

    /** 手机号自动建档：用户名 wx_ 前缀随机串，初始密码随机（仅本人可改密） */
    private SysUser registerByPhone(String phone) {
        SysUser user = new SysUser();
        user.setUsername("wx_" + RandomUtil.randomString(12));
        user.setNickname("微信用户");
        user.setMobile(phone);
        user.setPassword(BCrypt.hashpw(RandomUtil.randomString(32)));
        user.setStatus(CommonStatusEnum.ENABLED.getValue());
        userService.save(user);
        log.info("[PivotOS] 微信小程序手机号自动建档 userId={} mobile={}", user.getId(), phone);
        return user;
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

    /** wx-mini 体系登录并写入会话 */
    private String doLogin(SysUser user) {
        String token = StpWxMiniUtil.login(user.getId());
        AuthSessionHolder.saveLoginUser(StpWxMiniUtil.STP,
                new LoginUser(user.getId(), user.getUsername(), StpWxMiniUtil.TYPE, null));
        return token;
    }
}
