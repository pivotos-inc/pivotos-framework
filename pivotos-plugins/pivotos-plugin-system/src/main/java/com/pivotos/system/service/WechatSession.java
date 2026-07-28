package com.pivotos.system.service;

/** 微信 API 会话凭证（code2session 结果） */
public record WechatSession(String openId, String unionId) {
}
