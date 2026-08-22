-- migration_artifact.original_content / generated_content 由 TEXT 改为 LONGTEXT
-- 原因：源码文件（尤其是 IDE workspace.xml、SQL dump 等）可能超过 TEXT 的 65535 字节限制，
--       导致写入时报 DataIntegrityViolationException；LONGTEXT 支持最大 4GB，完全覆盖源码文件场景。
ALTER TABLE `migration_artifact`
    MODIFY COLUMN `original_content`   LONGTEXT DEFAULT NULL COMMENT '原代码内容（用于对比）',
    MODIFY COLUMN `generated_content`  LONGTEXT DEFAULT NULL COMMENT '生成的代码内容';
