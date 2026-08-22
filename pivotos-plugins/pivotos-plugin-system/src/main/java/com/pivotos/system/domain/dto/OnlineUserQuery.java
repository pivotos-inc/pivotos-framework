package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

import io.swagger.v3.oas.annotations.media.Schema;
/**
 * 在线用户分页查询
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class OnlineUserQuery extends PageQuery {

    /** 登录账号（模糊） */
    @Schema(description = "登录账号（模糊）")
    private String username;

    /** 登录 IP（模糊） */
    @Schema(description = "登录 IP（模糊）")
    private String ip;
}
