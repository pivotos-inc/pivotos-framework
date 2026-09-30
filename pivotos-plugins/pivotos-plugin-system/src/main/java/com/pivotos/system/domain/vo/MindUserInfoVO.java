package com.pivotos.system.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/** PivotOS·智域当前登录用户信息（简化版：不含角色权限） */
@Data
@AllArgsConstructor
public class MindUserInfoVO {

    /** 用户基本信息 */
    private UserVO user;

    /** 角色编码集合（个人端固定空） */
    private List<String> roles;

    /** 权限标识集合（个人端固定空） */
    private List<String> perms;
}
