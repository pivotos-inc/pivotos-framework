package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 用户实体 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_user")
public class SysUser extends BaseDO {

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 密码（BCrypt） */
    private String password;

    /** 部门ID */
    private Long deptId;

    /** 岗位ID */
    private Long postId;

    /** 邮箱 */
    private String email;

    /** 手机号 */
    private String mobile;

    /** 性别（0未知 1男 2女） */
    private Integer gender;

    /** 头像地址 */
    private String avatar;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}
