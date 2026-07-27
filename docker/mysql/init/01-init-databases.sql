-- PivotOS 开发库初始化（仅首次启动空数据卷时执行）
-- 业务表由 Flyway 在各应用启动时创建，本脚本只建库
CREATE DATABASE IF NOT EXISTS pivotos      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE DATABASE IF NOT EXISTS pivotos_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- XXL-Job 调度中心库（表结构首次启动若缺失，从镜像内 /xxl-job/db/tables_xxl_job.sql 导入）
CREATE DATABASE IF NOT EXISTS xxl_job      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
