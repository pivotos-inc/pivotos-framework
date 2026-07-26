-- =============================================================
-- PivotOS system 插件 · 基础数据（部门/角色/admin/字典/参数）
-- 说明：种子行使用固定字面 ID，create_by 统一记 1（admin）
-- =============================================================

-- 部门
INSERT INTO sys_dept (id, parent_id, dept_name, ancestors, leader_id, sort, status, create_by, create_time, deleted)
VALUES (1, 0, 'PivotOS 总部', '0', 1, 0, 0, 1, NOW(), 0);

-- 角色：超级管理员（通配权限 *:*:*，不插 sys_role_menu）
INSERT INTO sys_role (id, role_name, role_code, sort, status, remark, create_by, create_time, deleted)
VALUES (1, '超级管理员', 'super_admin', 1, 0, '拥有全部权限', 1, NOW(), 0);

-- 用户：admin / admin123（BCrypt 哈希，hutool BCrypt.checkpw 实测通过）
INSERT INTO sys_user (id, username, nickname, password, dept_id, email, mobile, gender, avatar, status, remark, create_by, create_time, deleted)
VALUES (1, 'admin', '超级管理员', '$2a$10$f6VSyer3piIbnul.xalnKOjLuu2QJUP1Ze39GhnEURSH1dL.jJ0om',
        1, 'admin@pivotos.com', '13800000000', 1, '', 0, '系统初始化超级管理员', 1, NOW(), 0);

-- admin 授予 super_admin
INSERT INTO sys_user_role (user_id, role_id) VALUES (1, 1);

-- 字典类型
INSERT INTO sys_dict_type (id, dict_name, dict_type, status, remark, create_by, create_time, deleted) VALUES
                                                                                                          (1, '通用状态', 'sys_common_status', 0, '0正常 1停用', 1, NOW(), 0),
                                                                                                          (2, '用户性别', 'sys_user_gender',   0, '0未知 1男 2女', 1, NOW(), 0),
                                                                                                          (3, '显隐状态', 'sys_show_hide',     0, '0显示 1隐藏', 1, NOW(), 0);

-- 字典数据
INSERT INTO sys_dict_data (id, dict_type, dict_label, dict_value, sort, status, create_by, create_time, deleted) VALUES
                                                                                                                     (1, 'sys_common_status', '正常', '0', 1, 0, 1, NOW(), 0),
                                                                                                                     (2, 'sys_common_status', '停用', '1', 2, 0, 1, NOW(), 0),
                                                                                                                     (3, 'sys_user_gender',   '未知', '0', 1, 0, 1, NOW(), 0),
                                                                                                                     (4, 'sys_user_gender',   '男',   '1', 2, 0, 1, NOW(), 0),
                                                                                                                     (5, 'sys_user_gender',   '女',   '2', 3, 0, 1, NOW(), 0),
                                                                                                                     (6, 'sys_show_hide',     '显示', '0', 1, 0, 1, NOW(), 0),
                                                                                                                     (7, 'sys_show_hide',     '隐藏', '1', 2, 0, 1, NOW(), 0);

-- 参数配置
INSERT INTO sys_config (id, config_name, config_key, config_value, config_type, remark, create_by, create_time, deleted) VALUES
                                                                                                                             (1, '用户初始密码', 'sys.user.initPassword', 'admin123', 'Y', '新建/重置用户时的初始密码', 1, NOW(), 0),
                                                                                                                             (2, '登录验证码开关', 'sys.account.captchaEnabled', 'false', 'Y', 'true 开启图形验证码（P1 实现）', 1, NOW(), 0);
