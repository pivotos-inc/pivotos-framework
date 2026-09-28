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
 *   <li>2080-2099 三方社交登录</li>
 *   <li>2100-2119 通知公告</li>
 *   <li>2120-2129 岗位管理</li>
 *   <li>2130-2139 在线用户</li>
 *   <li>2140-2149 定时任务</li>
 *   <li>2150-2159 租户与套餐</li>
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
    CONFIG_BUILTIN_FORBIDDEN(2072, "系统内置参数不允许删除"),

    // ---------- 三方社交登录 ----------
    SOCIAL_NOT_CONFIGURED(2080, "小程序登录未配置，请联系管理员"),
    SOCIAL_CODE_INVALID(2081, "微信授权码无效或已过期，请重试"),
    SOCIAL_API_FAILED(2082, "微信接口调用失败，请稍后重试"),
    SOCIAL_ALREADY_BOUND(2083, "该微信已绑定其他账号"),

    // ---------- 通知公告 ----------
    NOTICE_NOT_FOUND(2100, "公告不存在"),
    NOTICE_STATUS_INVALID(2101, "公告当前状态不允许该操作"),
    NOTICE_PUBLISHED_READONLY(2102, "已发布公告不允许编辑，请先撤回"),

    // ---------- 岗位管理 ----------
    POST_NOT_FOUND(2120, "岗位不存在"),
    POST_CODE_EXISTS(2121, "岗位编码已存在"),
    POST_HAS_USERS(2122, "岗位下存在用户，不允许删除"),

    // ---------- 在线用户 ----------
    ONLINE_USER_NOT_FOUND(2130, "在线用户不存在或已下线"),
    ONLINE_USER_KICKOUT_SELF(2131, "不能强退自己"),

    // ---------- 定时任务 ----------
    JOB_NOT_FOUND(2140, "任务不存在"),
    JOB_HANDLER_NOT_REGISTERED(2141, "Handler 未注册或任务未部署"),
    JOB_STATUS_RUNNING(2142, "任务运行中，无法编辑或删除，请先暂停"),
    JOB_SCHEDULE_INVALID(2143, "调度配置无效：Cron 表达式或固定速率不合法"),
    JOB_SYNC_FAILED(2144, "XXL-Job 调度中心同步失败，请检查调度中心是否在线"),
    JOB_ADMIN_UNREACHABLE(2145, "调度中心不可达，请检查 XXL-Job admin 配置"),

    // ---------- 租户与套餐 ----------
    TENANT_NOT_FOUND(2150, "租户不存在"),
    TENANT_CODE_EXISTS(2151, "租户编码已存在"),
    TENANT_HAS_USERS(2152, "租户下存在用户，不允许删除"),
    TENANT_DISABLED(2153, "租户已被停用，请联系平台管理员"),
    TENANT_EXPIRED(2154, "租户已过期，请联系平台管理员"),
    TENANT_ACCOUNT_LIMIT(2155, "租户账号数已达上限"),
    TENANT_PACKAGE_NOT_FOUND(2156, "租户套餐不存在"),
    TENANT_PACKAGE_IN_USE(2157, "套餐已被租户使用，不允许删除"),
    TENANT_PACKAGE_DISABLED(2158, "租户套餐已停用，不可绑定");

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
