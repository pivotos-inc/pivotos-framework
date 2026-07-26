package com.pivotos.system.constant;

/** system 域常量 */
public final class SystemConstants {

    /** 超级管理员角色编码 */
    public static final String SUPER_ADMIN_ROLE = "super_admin";

    /** 超级管理员通配权限串（Sa-Token 原生通配，匹配一切权限） */
    public static final String SUPER_ADMIN_PERM = "*:*:*";

    /** 初始密码配置键 */
    public static final String CONFIG_INIT_PASSWORD = "sys.user.initPassword";

    /** 默认初始密码（配置缺失时兜底） */
    public static final String DEFAULT_INIT_PASSWORD = "admin123";

    private SystemConstants() {
    }
}
