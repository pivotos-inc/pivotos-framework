package com.pivotos.system.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户分页查询 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserQuery extends PageQuery {

    /** 用户名（模糊） */
    private String username;

    /** 昵称（模糊） */
    private String nickname;

    /** 手机号（模糊） */
    private String mobile;

    /** 部门ID */
    private Long deptId;

    /** 岗位ID */
    private Long postId;

    /** 状态 */
    private Integer status;
}
