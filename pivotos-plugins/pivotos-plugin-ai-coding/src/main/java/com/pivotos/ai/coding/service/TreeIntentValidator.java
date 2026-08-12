package com.pivotos.ai.coding.service;

import com.pivotos.common.core.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.pivotos.ai.coding.api.constant.CodingErrorCode.CODING_RELATION_INVALID;

/**
 * 树表意图确定性校验器（S54 / tree intent）。
 * <p>
 * 不信 LLM 任何输出：标识符正则、树三字段存在性（treeCode/treeParentCode/treeName
 * 必须各自匹配 columns 中某个 columnName）、树字段互异、审计列兜底拒绝、
 * fk 目标表 information_schema 探测（不完整/不存在则降级丢弃 fk 配置，
 * 对齐 S50「fk 配置不完整按无 fk 处理」容错先例）。
 *
 * @author PivotOS
 * @since 2.4.0
 */
@Component
public class TreeIntentValidator {

    private static final Logger log = LoggerFactory.getLogger(TreeIntentValidator.class);

    private static final Pattern TABLE_OR_COLUMN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Pattern MODULE_OR_BUSINESS = Pattern.compile("^[a-z][a-zA-Z0-9]{0,31}$");

    /** 审计/主键列禁出（生成器自动补，LLM 给了即拒） */
    private static final Set<String> FORBIDDEN_COLUMNS = Set.of(
            "id", "create_by", "create_time", "update_by", "update_time", "deleted");

    private final JdbcTemplate jdbcTemplate;

    public TreeIntentValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 校验树表意图并就地规范化（fk 降级会直接改写入参 Map）。
     *
     * @param intent LLM 原始输出（单表 + treeCode/treeParentCode/treeName）
     * @throws ServiceException 7011 树字段不存在/标识符非法
     */
    @SuppressWarnings("unchecked")
    public void validate(Map<String, Object> intent) {
        String moduleName = asString(intent.get("moduleName"));
        String functionName = asString(intent.get("functionName"));
        if (moduleName == null || !MODULE_OR_BUSINESS.matcher(moduleName).matches()) {
            throw reject("moduleName 非法: " + moduleName);
        }
        if (functionName == null || functionName.isBlank()) {
            throw reject("functionName 为空");
        }

        String tableName = asString(intent.get("tableName"));
        String businessName = asString(intent.get("businessName"));
        if (tableName == null || !TABLE_OR_COLUMN.matcher(tableName).matches()) {
            throw reject("tableName 非法: " + tableName);
        }
        if (businessName == null || !MODULE_OR_BUSINESS.matcher(businessName).matches()) {
            throw reject("businessName 非法: " + businessName);
        }

        Object cols = intent.get("columns");
        if (!(cols instanceof List) || ((List<?>) cols).isEmpty()) {
            throw reject("columns 为空");
        }
        List<Map<String, Object>> columns = (List<Map<String, Object>>) cols;

        // 逐列校验标识符 + 审计列拒绝
        for (Map<String, Object> col : columns) {
            String columnName = asString(col.get("columnName"));
            if (columnName == null || !TABLE_OR_COLUMN.matcher(columnName).matches()) {
                throw reject("存在非法列名: " + columnName);
            }
            if (FORBIDDEN_COLUMNS.contains(columnName)) {
                throw reject("混入审计/主键列: " + columnName);
            }
            if (asString(col.get("javaType")) == null || asString(col.get("columnType")) == null
                    || asString(col.get("javaField")) == null) {
                throw reject(columnName + " 缺少 javaType/columnType/javaField");
            }
        }

        // 树三字段存在性 + 互异校验
        String treeCode = asString(intent.get("treeCode"));
        String treeParentCode = asString(intent.get("treeParentCode"));
        String treeName = asString(intent.get("treeName"));

        if (treeCode == null || !TABLE_OR_COLUMN.matcher(treeCode).matches()) {
            throw reject("treeCode 非法: " + treeCode);
        }
        if (treeParentCode == null || !TABLE_OR_COLUMN.matcher(treeParentCode).matches()) {
            throw reject("treeParentCode 非法: " + treeParentCode);
        }
        if (treeName == null || !TABLE_OR_COLUMN.matcher(treeName).matches()) {
            throw reject("treeName 非法: " + treeName);
        }

        // 互异：三字段不得重复
        if (treeCode.equals(treeParentCode) || treeCode.equals(treeName) || treeParentCode.equals(treeName)) {
            throw reject("树三字段必须互异: treeCode=" + treeCode
                    + ", treeParentCode=" + treeParentCode + ", treeName=" + treeName);
        }

        // 存在性：三字段必须各自匹配 columns 中某个 columnName
        Set<String> columnNames = new java.util.HashSet<>();
        for (Map<String, Object> col : columns) {
            columnNames.add(asString(col.get("columnName")));
        }
        if (!columnNames.contains(treeCode)) {
            throw reject("treeCode 不在 columns 中: " + treeCode);
        }
        if (!columnNames.contains(treeParentCode)) {
            throw reject("treeParentCode 不在 columns 中: " + treeParentCode);
        }
        if (!columnNames.contains(treeName)) {
            throw reject("treeName 不在 columns 中: " + treeName);
        }

        // fk 下拉配置：目标表+值列+显示列经 information_schema 探测，缺一则降级丢弃
        degradeFkColumns(tableName, columns);
    }

    /** fk 三键齐全且目标表/值列/显示列真实存在才保留，否则降级剥离 */
    private void degradeFkColumns(String tableName, List<Map<String, Object>> columns) {
        for (Map<String, Object> col : columns) {
            String fkTable = asString(col.get("fkTable"));
            String fkValue = asString(col.get("fkValueColumn"));
            String fkLabel = asString(col.get("fkLabelColumn"));
            if (fkTable == null && fkValue == null && fkLabel == null) {
                continue;
            }
            boolean keep = fkTable != null && fkValue != null && fkLabel != null
                    && TABLE_OR_COLUMN.matcher(fkTable).matches()
                    && columnExists(fkTable, fkValue) && columnExists(fkTable, fkLabel);
            if (!keep) {
                log.warn("[AI Coding] fk 配置降级丢弃: {}.{} -> {}/{}/{}",
                        tableName, col.get("columnName"), fkTable, fkValue, fkLabel);
                col.remove("fkTable");
                col.remove("fkValueColumn");
                col.remove("fkLabelColumn");
            }
        }
    }

    /** information_schema 探测目标列真实存在（查不到按不存在→降级） */
    private boolean columnExists(String table, String column) {
        try {
            Long cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE table_schema = (SELECT DATABASE()) AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    Long.class, table, column);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            log.warn("[AI Coding] fk 目标列探测失败，按不存在降级: {}.{}", table, column, e);
            return false;
        }
    }

    private static ServiceException reject(String reason) {
        log.warn("[AI Coding] 树意图校验拒绝: {}", reason);
        return new ServiceException(CODING_RELATION_INVALID);
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
