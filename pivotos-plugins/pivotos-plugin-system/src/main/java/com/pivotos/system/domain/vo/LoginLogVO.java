package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 登录日志视图对象 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LoginLogVO extends BaseDTO {

    /** 登录账号 */
    private String username;

    /** 登录 IP */
    private String ip;

    /** 浏览器 UA */
    private String userAgent;

    /** 结果（0成功 1失败） */
    private Integer status;

    /** 提示消息 */
    private String msg;

    /** 登录时间 */
    private LocalDateTime loginTime;
}
