package com.pivotos.system.service.impl;

import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import cn.hutool.core.date.DateUtil;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.domain.vo.OnlineUserVO;
import com.pivotos.system.service.OnlineUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 在线用户服务实现（基于 Sa-Token 会话，数据源自 Redis）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineUserServiceImpl implements OnlineUserService {

    private final RedissonClient redissonClient;
    private final SaTokenDao saTokenDao;

    /** Token 会话 Key 前缀（Sa-Token 多账号模式：{tokenName}:{loginType}:token-session:{token}） */
    private static final String TOKEN_SESSION_PREFIX = "Authorization:sys-user:token-session:";

    @Override
    public List<OnlineUserVO> listOnlineUsers() {
        List<OnlineUserVO> result = new ArrayList<>();
        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(TOKEN_SESSION_PREFIX + "*");

        for (String key : keys) {
            try {
                String tokenValue = key.substring(TOKEN_SESSION_PREFIX.length());
                OnlineUserVO vo = buildOnlineUser(tokenValue, key);
                if (vo != null) {
                    result.add(vo);
                }
            } catch (Exception e) {
                log.debug("扫描在线用户会话失败 key={}: {}", key, e.getMessage());
            }
        }
        return result;
    }

    @Override
    public void kickoutByToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isEmpty()) {
            throw new ServiceException(SystemErrorCode.ONLINE_USER_NOT_FOUND);
        }

        // 不能强退当前 Token 对应的会话
        String currentToken = StpSysUtil.STP.getTokenValue();
        if (tokenValue.equals(currentToken)) {
            throw new ServiceException(SystemErrorCode.ONLINE_USER_KICKOUT_SELF);
        }

        // 校验 token 属于 sys-user 体系且有效
        Object loginId = StpSysUtil.STP.getLoginIdByToken(tokenValue);
        if (loginId == null) {
            throw new ServiceException(SystemErrorCode.ONLINE_USER_NOT_FOUND);
        }

        StpSysUtil.STP.kickoutByTokenValue(tokenValue);
    }

    // ---- 私有 ----

    private OnlineUserVO buildOnlineUser(String tokenValue, String sessionKey) {
        // 1. 获取 Token 会话（含 LoginUser）
        SaSession tokenSession;
        try {
            tokenSession = StpSysUtil.STP.getTokenSessionByToken(tokenValue);
        } catch (Exception e) {
            log.debug("Token 会话已失效 token={}", tokenValue);
            return null;
        }
        if (tokenSession == null) {
            return null;
        }

        // 2. 提取 LoginUser（仅保留 sys-user 类型，过滤其他登录端）
        LoginUser loginUser;
        try {
            loginUser = (LoginUser) tokenSession.get("LOGIN_USER");
        } catch (Exception e) {
            log.debug("Token 会话中无 LOGIN_USER token={}", tokenValue);
            return null;
        }
        if (loginUser == null || loginUser.getUsername() == null
                || !"sys-user".equals(loginUser.getAccountType())) {
            return null;
        }

        // 3. 组装 VO
        OnlineUserVO vo = new OnlineUserVO();
        try {
            vo.setUserId(Long.valueOf(String.valueOf(loginUser.getUserId())));
        } catch (NumberFormatException e) {
            vo.setUserId(null);
        }
        vo.setUsername(loginUser.getUsername());
        vo.setTokenValue(maskToken(tokenValue));
        vo.setRawToken(tokenValue);

        // 登录 IP（S29 起在登录时写入）
        String ip = tokenSession.getString("LOGIN_IP");
        vo.setIpAddr(ip != null ? ip : "未知");

        // 登录时间
        Long loginTime = tokenSession.getLong("LOGIN_TIME");
        if (loginTime != null && loginTime > 0) {
            vo.setLoginTime(DateUtil.formatDateTime(new Date(loginTime)));
        } else {
            vo.setLoginTime(estimateLoginTime(sessionKey));
        }

        // 最后活跃时间（取 token session TTL 近似计算）
        long ttl = saTokenDao.getTimeout(sessionKey);
        if (ttl > 0) {
            long lastActive = System.currentTimeMillis() - (86400L - ttl) * 1000L;
            vo.setLastActiveTime(DateUtil.formatDateTime(new Date(lastActive)));
        } else {
            vo.setLastActiveTime(vo.getLoginTime());
        }

        return vo;
    }

    /** 根据 Redis key 的剩余 TTL 估算登录时间（用于旧会话未写 LOGIN_TIME 的兼容） */
    private String estimateLoginTime(String sessionKey) {
        long ttl = saTokenDao.getTimeout(sessionKey);
        if (ttl > 0 && ttl <= 86400) {
            long estimated = System.currentTimeMillis() - (86400L - ttl) * 1000L;
            return DateUtil.formatDateTime(new Date(estimated));
        }
        return "未知";
    }

    /** Token 掩码：前8位 + ... + 后8位 */
    private String maskToken(String token) {
        if (token == null || token.length() <= 16) {
            return token;
        }
        int prefix = Math.min(8, token.length() / 4);
        int suffix = Math.min(8, token.length() / 4);
        return token.substring(0, prefix) + "..." + token.substring(token.length() - suffix);
    }
}
