package com.pivotos.system.api.enums;

import com.pivotos.common.core.enums.error.ErrorCode;

/**
 * system 域错误码（2xxx 段）
 *
 * <p>号段分配：
 * <ul>
 *   <li>2000-2019 认证与用户</li>
 *   <li>2020-2039 角色与菜单</li>
 *   <li>2040-2059 部门</li>
 *   <li>2060-2079 字典与参数</li>
 * </ul>
 */
public enum SystemErrorCode implements ErrorCode {

    // ---------- 认证与用户 ----------
    LOGIN_FAILED(2001, "账号或密码错误"),
    USER_DISABLED(2002, "账号已被停用，请联系管理员"),
    USERNAME_EXISTS(2003, "用户名已存在"),
    USER_NOT_FOUND(2004, "用户不存在"),
    OLD_PASSWORD_ERROR(2005, "旧密码错误"),
    SUPER_ADMIN_FORBIDDEN(2006, "不允许操作超级管理员"),

    // ---------- 角色与菜单 ----------
    ROLE_NOT_FOUND(2020, "角色不存在"),
    ROLE_CODE_EXISTS(2021, "角色编码已存在"),
    ROLE_ASSIGNED(2022, "角色已分配用户，不允许删除"),
    MENU_NOT_FOUND(2030, "菜单不存在"),
    MENU_HAS_CHILDREN(2031, "存在子菜单，不允许删除"),
    MENU_ASSIGNED(2032, "菜单已分配角色，不允许删除"),

    // ---------- 部门 ----------
    DEPT_NOT_FOUND(2040, "部门不存在"),
    DEPT_HAS_CHILDREN(2041, "存在子部门，不允许删除"),
    DEPT_HAS_USERS(2042, "部门下存在用户，不允许删除"),
    DEPT_PARENT_INVALID(2043, "父部门不能选择自己或其子部门"),

    // ---------- 字典与参数 ----------
    DICT_TYPE_EXISTS(2060, "字典类型已存在"),
    DICT_TYPE_NOT_FOUND(2061, "字典类型不存在"),
    DICT_DATA_NOT_FOUND(2062, "字典数据不存在"),
    CONFIG_KEY_EXISTS(2070, "参数键名已存在"),
    CONFIG_NOT_FOUND(2071, "参数不存在"),
    CONFIG_BUILTIN_FORBIDDEN(2072, "系统内置参数不允许删除");

    private final int code;
    private final String msg;

    SystemErrorCode(int code, String msg) {
        this.code = code;
        this.msg = msg;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMsg() {
        return msg;
    }
}
