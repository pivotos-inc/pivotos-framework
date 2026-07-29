package com.pivotos.system.service.impl;

import cn.hutool.http.HttpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.config.WechatMiniProperties;
import com.pivotos.system.service.WechatMiniService;
import com.pivotos.system.service.WechatSession;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 微信 API 实现（Hutool HTTP 直连）。
 * access_token 单体内存缓存（提前 5 分钟过期）；多实例部署时换 Redis 缓存即可。
 */
@Service
@RequiredArgsConstructor
public class WechatMiniServiceImpl implements WechatMiniService {

    private static final Logger log = LoggerFactory.getLogger(WechatMiniServiceImpl.class);

    private static final String CODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String ACCESS_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String PHONE_URL = "https://api.weixin.qq.com/wxa/business/getuserphonenumber";

    private final WechatMiniProperties properties;

    /** access_token 内存缓存（token, 过期时间戳毫秒） */
    private volatile String cachedToken;
    private volatile long cachedTokenExpireAt;

    @Override
    public WechatSession code2Session(String code) {
        requireConfigured();
        String body = HttpUtil.get(CODE2SESSION_URL, Map.of(
                "appid", properties.getAppid(),
                "secret", properties.getSecret(),
                "js_code", code,
                "grant_type", "authorization_code"));
        JSONObject json = parse(body);
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode != 0) {
            // 40029=code 无效 / 40163=code 已使用，归为客户端可重试错误
            log.warn("[PivotOS] code2session 失败 errcode={} errmsg={}", errcode, json.getString("errmsg"));
            throw new ServiceException(SystemErrorCode.SOCIAL_CODE_INVALID);
        }
        String openId = json.getString("openid");
        if (openId == null || openId.isBlank()) {
            throw new ServiceException(SystemErrorCode.SOCIAL_API_FAILED);
        }
        return new WechatSession(openId, json.getString("unionid"));
    }

    @Override
    public String getPhoneNumber(String phoneCode) {
        requireConfigured();
        String body = HttpUtil.post(PHONE_URL + "?access_token=" + accessToken(),
                JSON.toJSONString(Map.of("code", phoneCode)));
        JSONObject json = parse(body);
        Integer errcode = json.getInteger("errcode");
        if (errcode == null || errcode != 0) {
            log.warn("[PivotOS] getuserphonenumber 失败 errcode={} errmsg={}", errcode, json.getString("errmsg"));
            throw new ServiceException(SystemErrorCode.SOCIAL_CODE_INVALID);
        }
        String phone = json.getJSONObject("phone_info") == null ? null
                : json.getJSONObject("phone_info").getString("purePhoneNumber");
        if (phone == null || phone.isBlank()) {
            throw new ServiceException(SystemErrorCode.SOCIAL_API_FAILED);
        }
        return phone;
    }

    /** client_credential 取 access_token，内存缓存 + 双检 */
    private String accessToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < cachedTokenExpireAt) {
            return cachedToken;
        }
        synchronized (this) {
            if (cachedToken != null && System.currentTimeMillis() < cachedTokenExpireAt) {
                return cachedToken;
            }
            String body = HttpUtil.get(ACCESS_TOKEN_URL, Map.of(
                    "grant_type", "client_credential",
                    "appid", properties.getAppid(),
                    "secret", properties.getSecret()));
            JSONObject json = parse(body);
            String token = json.getString("access_token");
            if (token == null || token.isBlank()) {
                log.error("[PivotOS] 获取微信 access_token 失败：{}", body);
                throw new ServiceException(SystemErrorCode.SOCIAL_API_FAILED);
            }
            cachedToken = token;
            // 提前 5 分钟过期，抵消时钟偏移与在途请求
            cachedTokenExpireAt = System.currentTimeMillis()
                    + (json.getLongValue("expires_in", 7200L) - 300) * 1000;
            return token;
        }
    }

    private void requireConfigured() {
        if (!properties.configured()) {
            throw new ServiceException(SystemErrorCode.SOCIAL_NOT_CONFIGURED);
        }
    }

    private JSONObject parse(String body) {
        try {
            return JSON.parseObject(body);
        } catch (Exception e) {
            log.error("[PivotOS] 微信接口响应解析失败：{}", body, e);
            throw new ServiceException(SystemErrorCode.SOCIAL_API_FAILED);
        }
    }
}
