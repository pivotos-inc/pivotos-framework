package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 三方社交账号绑定实体 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_social_user")
public class SysSocialUser extends BaseDO {

    /** 系统用户ID（sys_user.id） */
    private Long userId;

    /** 渠道（wechat-mini / alipay-mini） */
    private String channel;

    /** 渠道内用户标识（openid） */
    private String openId;

    /** 开放平台 unionId（跨小程序打通，可空） */
    private String unionId;
}
