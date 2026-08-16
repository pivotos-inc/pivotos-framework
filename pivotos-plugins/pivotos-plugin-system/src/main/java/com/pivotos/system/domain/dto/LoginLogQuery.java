package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/** 登录日志分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class LoginLogQuery extends PageQuery {

    /** 登录账号（模糊） */
    @Schema(description = "登录账号（模糊）")
    private String username;

    /** 登录 IP（模糊） */
    @Schema(description = "登录 IP（模糊）")
    private String ip;

    /** 结果（0成功 1失败） */
    @Schema(description = "结果（0成功 1失败）")
    private Integer status;

    /** 登录时间起 */
    @Schema(description = "登录时间起")
    private LocalDateTime beginTime;

    /** 登录时间止 */
    @Schema(description = "登录时间止")
    private LocalDateTime endTime;
}
