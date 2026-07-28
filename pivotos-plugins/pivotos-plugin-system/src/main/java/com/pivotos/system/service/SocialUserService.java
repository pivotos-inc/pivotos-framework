package com.pivotos.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.system.domain.entity.SysSocialUser;

/** 三方社交账号绑定服务 */
public interface SocialUserService extends IService<SysSocialUser> {

    /** 按 渠道+openId 查绑定记录，未绑定返回 null */
    SysSocialUser findByChannelOpenId(String channel, String openId);

    /** 建立绑定（同一渠道身份重复绑定报 2083） */
    void bind(Long userId, String channel, String openId, String unionId);
}
