package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** 角色新增/修改请求 */
@Data
public class RoleSaveRequest {

    /** 角色ID */
    @Schema(description = "角色ID")
    private Long id;

    /** 角色名称 */
    @NotBlank(message = "角色名称不能为空")
    private String roleName;

    /** 角色编码 */
    @NotBlank(message = "角色编码不能为空")
    private String roleCode;

    /** 显示顺序 */
    @Schema(description = "显示顺序")
    private Integer sort;

    /** 状态（0正常 1停用） */
    @Schema(description = "状态（0正常 1停用）")
    private Integer status;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;

    /** 菜单ID集合（授权） */
    @Schema(description = "菜单ID集合（授权）")
    private List<Long> menuIds;

    /** 数据范围（1全部数据权限 2本部门 3本部门及以下 4仅本人 5自定义部门） */
    @Schema(description = "数据范围（1全部数据权限 2本部门 3本部门及以下 4仅本人 5自定义部门）")
    private Integer dataScope;

    /** 自定义部门ID集合（逗号分隔），data_scope=5时有效 */
    @Schema(description = "自定义部门ID集合（逗号分隔），data_scope=5时有效")
    private String customDeptIds;
}
