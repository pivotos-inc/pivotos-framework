package com.pivotos.system.api.dto;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 用户传输对象（不含密码等敏感字段）
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserDTO extends BaseDTO {

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 部门 ID */
    private Long deptId;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String mobile;

    /** 性别（0 未知 1 男 2 女） */
    private Integer gender;

    /** 头像地址 */
    private String avatar;

    /** 状态（0 正常 1 停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}
