-- =====================================================
-- v2.4.0 (S50 / 2.4-F1)：生成器多表数据模型扩展
-- sys_gen_table       + 模板类型/树配置/主子配置
-- sys_gen_table_column + fk 关联下拉配置
-- 存量行兼容：tpl_category 默认 'crud'，语义与旧单表一致
-- =====================================================

ALTER TABLE `sys_gen_table`
    ADD COLUMN `tpl_category`       VARCHAR(16) NOT NULL DEFAULT 'crud' COMMENT '模板类型（crud单表 tree树表 sub主子表）' AFTER `remark`,
    ADD COLUMN `tree_code`          VARCHAR(64) DEFAULT NULL COMMENT '树编码字段（tpl_category=tree）' AFTER `tpl_category`,
    ADD COLUMN `tree_parent_code`   VARCHAR(64) DEFAULT NULL COMMENT '树父编码字段（tpl_category=tree）' AFTER `tree_code`,
    ADD COLUMN `tree_name`          VARCHAR(64) DEFAULT NULL COMMENT '树名称字段（tpl_category=tree）' AFTER `tree_parent_code`,
    ADD COLUMN `sub_table_name`     VARCHAR(64) DEFAULT NULL COMMENT '子表名（tpl_category=sub）' AFTER `tree_name`,
    ADD COLUMN `sub_table_fk_name`  VARCHAR(64) DEFAULT NULL COMMENT '子表外键列名（tpl_category=sub）' AFTER `sub_table_name`;

ALTER TABLE `sys_gen_table_column`
    ADD COLUMN `fk_table`        VARCHAR(64) DEFAULT NULL COMMENT '关联表名（fk 关联下拉）' AFTER `dict_type`,
    ADD COLUMN `fk_value_column` VARCHAR(64) DEFAULT NULL COMMENT '关联值列' AFTER `fk_table`,
    ADD COLUMN `fk_label_column` VARCHAR(64) DEFAULT NULL COMMENT '关联显示列' AFTER `fk_value_column`;
