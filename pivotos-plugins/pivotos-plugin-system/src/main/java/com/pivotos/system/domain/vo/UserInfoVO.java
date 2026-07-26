package com.pivotos.system.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** 当前登录用户信息（用户 + 角色 + 权限串） */
@Data
@AllArgsConstructor
public class UserInfoVO {

    /** 用户基本信息 */
    private UserVO user;

    /** 角色编码集合（超管为 ["super_admin"]） */
    private List<String> roles;

    /** 权限标识集合（超管为 ["*:*:*"]） */
    private List<String> perms;
}
