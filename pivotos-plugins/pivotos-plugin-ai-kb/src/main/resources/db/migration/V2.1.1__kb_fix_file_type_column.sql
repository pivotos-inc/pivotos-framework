-- V2.1.1  修复 file_type 列过短导致 docx MIME 类型截断（application/vnd.openxmlformats-... 达 71 字符）
ALTER TABLE ai_kb_document MODIFY COLUMN file_type VARCHAR(200) DEFAULT NULL COMMENT '文件 MIME 类型';
