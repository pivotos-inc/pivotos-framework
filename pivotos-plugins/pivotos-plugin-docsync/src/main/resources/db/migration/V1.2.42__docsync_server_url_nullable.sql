-- server_url 改为允许 NULL：Apifox 等平台不需要此字段，由适配器 getConfigFields() 平台级校验
ALTER TABLE `doc_sync_config`
    MODIFY COLUMN `server_url` VARCHAR(500) DEFAULT NULL COMMENT '服务器地址（平台级校验，部分平台无需此字段）';
