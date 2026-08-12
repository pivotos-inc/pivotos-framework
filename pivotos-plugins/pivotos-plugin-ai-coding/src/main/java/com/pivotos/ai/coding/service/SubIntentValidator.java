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
 * 主子表多表意图确定性校验器（S52 / 2.4-F5）。
 * <p>
 * 不信 LLM 任何输出：标识符正则、关系闭合（subFkName 必须落在 sub.columns）、
 * 审计列兜底拒绝、fk 目标表 information_schema 探测（不完整/不存在则降级丢弃 fk 配置，
 * 对齐 S50「fk 配置不完整按无 fk 处理」容错先例）。
 *
 * @author PivotOS
 * @since 2.4.0
 */
@Component
public class SubIntentValidator {

    private static final Logger log = LoggerFactory.getLogger(SubIntentValidator.class);

    private static final Pattern TABLE_OR_COLUMN = Pattern.compile("^[a-z][a-z0-9_]{1,63}$");
    private static final Pattern MODULE_OR_BUSINESS = Pattern.compile("^[a-z][a-zA-Z0-9]{0,31}$");

    /** 审计/主键列禁出（生成器自动补，LLM 给了即拒） */
    private static final Set<String> FORBIDDEN_COLUMNS = Set.of(
            "id", "create_by", "create_time", "update_by", "update_time", "deleted");

    private final JdbcTemplate jdbcTemplate;

    public SubIntentValidator(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 校验主子意图并就地规范化（fk 降级会直接改写入参 Map）。
     *
     * @param intent LLM 原始输出（main/sub/relation 结构）
     * @throws ServiceException 7011 关系不闭合或命名非法
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

        Map<String, Object> main = asMap(intent.get("main"));
        Map<String, Object> sub = asMap(intent.get("sub"));
        Map<String, Object> relation = asMap(intent.get("relation"));
        if (main == null || sub == null || relation == null) {
            throw reject("缺少 main/sub/relation 结构");
        }

        String mainTable = validateTableBlock(main, "main");
        String subTable = validateTableBlock(sub, "sub");
        if (mainTable.equals(subTable)) {
            throw reject("主子表同名: " + mainTable);
        }

        // 关系闭合：fk 列名合法 + 落在子表列内
        String subFkName = asString(relation.get("subFkName"));
        if (subFkName == null || !TABLE_OR_COLUMN.matcher(subFkName).matches()) {
            throw reject("relation.subFkName 非法: " + subFkName);
        }
        List<Map<String, Object>> subColumns = (List<Map<String, Object>>) sub.get("columns");
        boolean fkClosed = subColumns.stream()
                .anyMatch(c -> subFkName.equals(asString(c.get("columnName"))));
        if (!fkClosed) {
            throw reject("关系不闭合: " + subTable + " 缺少 fk 列 " + subFkName);
        }
        // fk 列自身不得配置为业务 fk 下拉列（语义冲突）
        for (Map<String, Object> col : subColumns) {
            if (subFkName.equals(asString(col.get("columnName")))) {
                col.remove("fkTable");
                col.remove("fkValueColumn");
                col.remove("fkLabelColumn");
            }
        }

        // fk 下拉配置：目标表+值列+显示列经 information_schema 探测，缺一则降级丢弃
        degradeFkColumns(main);
        degradeFkColumns(sub);
    }

    /** 校验单个表块（tableName/businessName/columns），返回 tableName */
    @SuppressWarnings("unchecked")
    private String validateTableBlock(Map<String, Object> block, String tag) {
        String tableName = asString(block.get("tableName"));
        String businessName = asString(block.get("businessName"));
        if (tableName == null || !TABLE_OR_COLUMN.matcher(tableName).matches()) {
            throw reject(tag + ".tableName 非法: " + tableName);
        }
        if (businessName == null || !MODULE_OR_BUSINESS.matcher(businessName).matches()) {
            throw reject(tag + ".businessName 非法: " + businessName);
        }
        Object cols = block.get("columns");
        if (!(cols instanceof List) || ((List<?>) cols).isEmpty()) {
            throw reject(tag + ".columns 为空");
        }
        for (Map<String, Object> col : (List<Map<String, Object>>) cols) {
            String columnName = asString(col.get("columnName"));
            if (columnName == null || !TABLE_OR_COLUMN.matcher(columnName).matches()) {
                throw reject(tag + " 存在非法列名: " + columnName);
            }
            if (FORBIDDEN_COLUMNS.contains(columnName)) {
                throw reject(tag + " 混入审计/主键列: " + columnName);
            }
            if (asString(col.get("javaType")) == null || asString(col.get("columnType")) == null
                    || asString(col.get("javaField")) == null) {
                throw reject(tag + "." + columnName + " 缺少 javaType/columnType/javaField");
            }
        }
        return tableName;
    }

    /** fk 三键齐全且目标表/值列/显示列真实存在才保留，否则降级剥离 */
    private void degradeFkColumns(Map<String, Object> tableBlock) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> columns = (List<Map<String, Object>>) tableBlock.get("columns");
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
                        tableBlock.get("tableName"), col.get("columnName"), fkTable, fkValue, fkLabel);
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
        log.warn("[AI Coding] 多表意图校验拒绝: {}", reason);
        return new ServiceException(CODING_RELATION_INVALID);
    }

    private static String asString(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
