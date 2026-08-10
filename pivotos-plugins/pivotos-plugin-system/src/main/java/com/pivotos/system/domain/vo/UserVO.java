package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户视图对象（脱敏，不含密码） */
@Data
@EqualsAndHashCode(callSuper = true)
public class UserVO extends BaseDTO {

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 部门ID */
    private Long deptId;

    /** 岗位ID */
    private Long postId;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String mobile;

    /** 性别 */
    private Integer gender;

    /** 头像地址 */
    private String avatar;

    /** 状态 */
    private Integer status;

    /** 备注 */
    private String remark;
}
