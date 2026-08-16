package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 用户新增/修改请求（id 为空为新增；password 仅新增时必填） */
@Data
public class UserSaveRequest {

    /** 用户ID */
    @Schema(description = "用户ID")
    private Long id;

    /** 用户名 */
    @NotBlank(message = "用户名不能为空")
    private String username;

    /** 昵称 */
    @NotBlank(message = "昵称不能为空")
    private String nickname;

    /** 密码（新增必填，修改留空表示不变） */
    @Schema(description = "密码（新增必填，修改留空表示不变）")
    private String password;

    /** 部门ID */
    @Schema(description = "部门ID")
    private Long deptId;

    /** 岗位ID */
    @Schema(description = "岗位ID")
    private Long postId;

    /** 邮箱 */
    @Schema(description = "邮箱")
    private String email;

    /** 手机号 */
    @Schema(description = "手机号")
    private String mobile;

    /** 性别（0未知 1男 2女） */
    @Schema(description = "性别（0未知 1男 2女）")
    private Integer gender;

    /** 头像地址 */
    @Schema(description = "头像地址")
    private String avatar;

    /** 状态（0正常 1停用） */
    @Schema(description = "状态（0正常 1停用）")
    private Integer status;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;

    /** 角色ID集合 */
    @Schema(description = "角色ID集合")
    private List<Long> roleIds;
}
