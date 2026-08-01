package com.pivotos.system.service.impl;

import cn.dev33.satoken.config.SaTokenConfig;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.session.SaSession;
import cn.hutool.core.date.DateUtil;
import cn.hutool.core.util.StrUtil;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.domain.dto.OnlineUserQuery;
import com.pivotos.system.domain.vo.OnlineUserVO;
import com.pivotos.system.service.OnlineUserService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;

/**
 * 在线用户服务实现（基于 Sa-Token 会话，数据源自 Redis）。
 */
@Slf4j
@Service
public class OnlineUserServiceImpl implements OnlineUserService {

    private final RedissonClient redissonClient;
    private final SaTokenDao saTokenDao;
    private final long tokenTimeout;

    /** Token 会话 Key 前缀（Sa-Token 多账号模式：{tokenName}:{loginType}:token-session:{token}） */
    private static final String TOKEN_SESSION_PREFIX = "Authorization:sys-user:token-session:";

    /** 会话中存放的 LoginUser 对象 Key */
    private static final String LOGIN_USER_KEY = "LOGIN_USER";

    /** 会话中存放的登录 IP Key */
    private static final String LOGIN_IP_KEY = "LOGIN_IP";

    /** 会话中存放的登录时间 Key */
    private static final String LOGIN_TIME_KEY = "LOGIN_TIME";

    /** 会话中存放的最后活跃时间 Key */
    private static final String LAST_ACTIVE_TIME_KEY = "LAST_ACTIVE_TIME";

    public OnlineUserServiceImpl(RedissonClient redissonClient, SaTokenDao saTokenDao,
                                 SaTokenConfig saTokenConfig) {
        this.redissonClient = redissonClient;
        this.saTokenDao = saTokenDao;
        this.tokenTimeout = saTokenConfig.getTimeout();
    }

    @Override
    public PageResult<OnlineUserVO> pageOnlineUsers(OnlineUserQuery query) {
        List<OnlineUserVO> all = collectOnlineUsers(query);

        // 1. 按登录时间倒序（最近登录在前）
        all.sort(Comparator.comparing(OnlineUserVO::getLoginTime,
                Comparator.nullsLast(Comparator.reverseOrder())));

        // 2. 内存分页
        int total = all.size();
        int pageNum = query.getPageNum();
        int pageSize = query.getPageSize();
        int fromIndex = Math.min((pageNum - 1) * pageSize, total);
        int toIndex = Math.min(fromIndex + pageSize, total);
        List<OnlineUserVO> list = fromIndex < toIndex ? all.subList(fromIndex, toIndex) : new ArrayList<>();

        return new PageResult<>(list, (long) total, pageNum, pageSize);
    }

    /**
     * 收集所有 sys-user 在线用户（含按 username/ip 过滤）。
     */
    private List<OnlineUserVO> collectOnlineUsers(OnlineUserQuery query) {
        List<OnlineUserVO> result = new ArrayList<>();
        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(TOKEN_SESSION_PREFIX + "*");

        for (String key : keys) {
            try {
                String tokenValue = key.substring(TOKEN_SESSION_PREFIX.length());
                OnlineUserVO vo = buildOnlineUser(tokenValue, key, query);
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

    @Override
    public int clearAllUsers() {
        String currentToken = StpSysUtil.STP.getTokenValue();
        int count = 0;
        Iterable<String> keys = redissonClient.getKeys().getKeysByPattern(TOKEN_SESSION_PREFIX + "*");

        for (String key : keys) {
            try {
                String tokenValue = key.substring(TOKEN_SESSION_PREFIX.length());
                // 跳过当前用户
                if (tokenValue.equals(currentToken)) {
                    continue;
                }
                // 仅处理有效的 sys-user 会话
                Object loginId = StpSysUtil.STP.getLoginIdByToken(tokenValue);
                if (loginId == null) {
                    continue;
                }
                StpSysUtil.STP.kickoutByTokenValue(tokenValue);
                count++;
            } catch (Exception e) {
                log.debug("清空在线用户时跳过 key={}: {}", key, e.getMessage());
            }
        }
        log.info("清空在线用户完成，共强退 {} 个会话", count);
        return count;
    }

    // ---- 私有 ----

    private OnlineUserVO buildOnlineUser(String tokenValue, String sessionKey, OnlineUserQuery query) {
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
            loginUser = (LoginUser) tokenSession.get(LOGIN_USER_KEY);
        } catch (Exception e) {
            log.debug("Token 会话中无 LOGIN_USER token={}", tokenValue);
            return null;
        }
        if (loginUser == null || loginUser.getUsername() == null
                || !StpSysUtil.TYPE.equals(loginUser.getAccountType())) {
            return null;
        }

        // 3. 获取 Token Session 剩余有效期（秒）
        long ttl = saTokenDao.getTimeout(sessionKey);

        // 3.1 过滤已过期的会话（TTL <= 0 且不是持久化 key）
        if (ttl == -2) {
            // key 存在但无过期时间（持久化），视为有效
            ttl = -1;
        } else if (ttl <= 0) {
            log.debug("Token 会话已过期 token={} ttl={}", tokenValue, ttl);
            return null;
        }

        // 4. 组装 VO
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
        String ip = tokenSession.getString(LOGIN_IP_KEY);
        vo.setIpAddr(ip != null ? ip : "未知");

        // 登录时间
        Long loginTimeMsObj = tokenSession.getLong(LOGIN_TIME_KEY);
        long loginTimeMs = (loginTimeMsObj != null && loginTimeMsObj > 0) ? loginTimeMsObj : 0;
        if (loginTimeMs > 0) {
            vo.setLoginTime(DateUtil.formatDateTime(new Date(loginTimeMs)));
        } else {
            vo.setLoginTime(estimateLoginTime(sessionKey));
            loginTimeMs = estimateLoginTimeMillis(sessionKey);
        }

        // 最后活跃时间：优先使用精确记录值，其次用 TTL 估算
        Long lastActiveMsObj = tokenSession.getLong(LAST_ACTIVE_TIME_KEY);
        long lastActiveMs;
        if (lastActiveMsObj != null && lastActiveMsObj > 0) {
            // 有精确记录的最后活跃时间
            lastActiveMs = lastActiveMsObj;
        } else if (ttl > 0 && ttl <= tokenTimeout) {
            // 从 TTL 估算：total_timeout - remaining_ttl = 已消耗时间 → 最后刷新 = now - 已消耗
            lastActiveMs = System.currentTimeMillis() - (tokenTimeout - ttl) * 1000L;
            // 确保不会早于登录时间（修复 S29 留存的时序倒挂）
            if (loginTimeMs > 0 && lastActiveMs < loginTimeMs) {
                lastActiveMs = loginTimeMs;
            }
        } else {
            lastActiveMs = loginTimeMs > 0 ? loginTimeMs : System.currentTimeMillis();
        }
        vo.setLastActiveTime(DateUtil.formatDateTime(new Date(lastActiveMs)));

        // Token 剩余有效期（秒）
        vo.setTokenTtl(ttl > 0 ? ttl : -1L);

        // 5. 按 username / ip 过滤
        if (query != null) {
            if (StrUtil.isNotBlank(query.getUsername())
                    && !StrUtil.containsIgnoreCase(vo.getUsername(), query.getUsername())) {
                return null;
            }
            if (StrUtil.isNotBlank(query.getIp())
                    && !StrUtil.containsIgnoreCase(vo.getIpAddr(), query.getIp())) {
                return null;
            }
        }

        return vo;
    }

    /** 根据 Redis key 的剩余 TTL 估算登录时间（用于旧会话未写 LOGIN_TIME 的兼容） */
    private String estimateLoginTime(String sessionKey) {
        long ms = estimateLoginTimeMillis(sessionKey);
        if (ms > 0) {
            return DateUtil.formatDateTime(new Date(ms));
        }
        return "未知";
    }

    private long estimateLoginTimeMillis(String sessionKey) {
        long ttl = saTokenDao.getTimeout(sessionKey);
        if (ttl > 0 && ttl <= tokenTimeout) {
            return System.currentTimeMillis() - (tokenTimeout - ttl) * 1000L;
        }
        return 0;
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
