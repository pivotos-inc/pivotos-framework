package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 登录日志实体（平台共享表，已登记租户内置忽略清单） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_login_log")
public class SysLoginLog extends BaseDO {

    /** 登录账号 */
    private String username;

    /** 登录 IP */
    private String ip;

    /** 浏览器 UA */
    private String userAgent;

    /** 结果（0成功 1失败） */
    private Integer status;

    /** 提示消息（失败原因等） */
    private String msg;

    /** 登录时间 */
    private LocalDateTime loginTime;
}
