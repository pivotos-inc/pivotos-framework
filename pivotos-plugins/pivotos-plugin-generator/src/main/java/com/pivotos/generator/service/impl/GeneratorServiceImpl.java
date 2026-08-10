package com.pivotos.generator.service.impl;

import cn.hutool.core.date.DateUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.generator.api.enums.GeneratorErrorCode;
import com.pivotos.generator.domain.entity.GenTable;
import com.pivotos.generator.domain.entity.GenTableColumn;
import com.pivotos.generator.mapper.GenTableColumnMapper;
import com.pivotos.generator.mapper.GenTableMapper;
import com.pivotos.generator.service.GeneratorService;
import freemarker.template.Configuration;
import freemarker.template.Template;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 代码生成器服务实现
 */
@Slf4j
@Service
public class GeneratorServiceImpl implements GeneratorService {

    /** 数据库字段类型 → Java 类型映射 */
    private static final Map<String, String> JAVA_TYPE_MAP = new LinkedHashMap<>();

    /** 需要导入包的 Java 类型 */
    private static final Set<String> IMPORTABLE_TYPES = Set.of(
            "BigDecimal", "LocalDateTime", "LocalDate", "LocalTime"
    );

    static {
        JAVA_TYPE_MAP.put("varchar", "String");
        JAVA_TYPE_MAP.put("char", "String");
        JAVA_TYPE_MAP.put("text", "String");
        JAVA_TYPE_MAP.put("longtext", "String");
        JAVA_TYPE_MAP.put("int", "Integer");
        JAVA_TYPE_MAP.put("integer", "Integer");
        JAVA_TYPE_MAP.put("tinyint", "Integer");
        JAVA_TYPE_MAP.put("smallint", "Integer");
        JAVA_TYPE_MAP.put("bigint", "Long");
        JAVA_TYPE_MAP.put("decimal", "BigDecimal");
        JAVA_TYPE_MAP.put("numeric", "BigDecimal");
        JAVA_TYPE_MAP.put("datetime", "LocalDateTime");
        JAVA_TYPE_MAP.put("timestamp", "LocalDateTime");
        JAVA_TYPE_MAP.put("date", "LocalDate");
        JAVA_TYPE_MAP.put("time", "LocalTime");
        JAVA_TYPE_MAP.put("bit", "Boolean");
        JAVA_TYPE_MAP.put("float", "Float");
        JAVA_TYPE_MAP.put("double", "Double");
    }

    /** DTO 需要跳过的字段（BaseDO / BaseQuery 已有） */
    private static final Set<String> BASE_DO_FIELDS = Set.of(
            "id", "createBy", "createTime", "updateBy", "updateTime", "deleted"
    );

    /** Java 类型 → TypeScript 类型映射 */
    private static final Map<String, String> TS_TYPE_MAP = new LinkedHashMap<>();

    static {
        TS_TYPE_MAP.put("String", "string");
        TS_TYPE_MAP.put("Integer", "number");
        TS_TYPE_MAP.put("Long", "number");
        TS_TYPE_MAP.put("BigDecimal", "number");
        TS_TYPE_MAP.put("Float", "number");
        TS_TYPE_MAP.put("Double", "number");
        TS_TYPE_MAP.put("Boolean", "boolean");
        TS_TYPE_MAP.put("LocalDateTime", "string");
        TS_TYPE_MAP.put("LocalDate", "string");
        TS_TYPE_MAP.put("LocalTime", "string");
    }

    @Resource
    private GenTableMapper genTableMapper;

    @Resource
    private GenTableColumnMapper genTableColumnMapper;

    @Resource
    private JdbcTemplate jdbcTemplate;

    private Configuration freemarkerConfig;

    @PostConstruct
    public void initFreeMarker() {
        freemarkerConfig = new Configuration(Configuration.DEFAULT_INCOMPATIBLE_IMPROVEMENTS);
        freemarkerConfig.setClassLoaderForTemplateLoading(
                getClass().getClassLoader(), "templates/");
        freemarkerConfig.setDefaultEncoding("UTF-8");
    }

    // ==================== 数据库表查询 ====================

    @Override
    public IPage<Map<String, Object>> selectDbTableList(IPage<Map<String, Object>> page,
                                                         String tableName, String tableComment) {
        StringBuilder sql = new StringBuilder(
                "SELECT TABLE_NAME AS table_name, TABLE_COMMENT AS table_comment, " +
                "CREATE_TIME AS create_time, UPDATE_TIME AS update_time " +
                "FROM information_schema.tables " +
                "WHERE table_schema = (SELECT DATABASE()) " +
                "  AND TABLE_NAME NOT LIKE 'sys_gen_%' " +
                "  AND TABLE_NAME NOT LIKE 'quartz_%'"
        );
        List<Object> params = new ArrayList<>();
        if (StrUtil.isNotBlank(tableName)) {
            sql.append(" AND TABLE_NAME LIKE ?");
            params.add("%" + tableName + "%");
        }
        if (StrUtil.isNotBlank(tableComment)) {
            sql.append(" AND TABLE_COMMENT LIKE ?");
            params.add("%" + tableComment + "%");
        }
        sql.append(" ORDER BY CREATE_TIME DESC");

        // 查询总数
        String countSql = "SELECT COUNT(*) FROM (" + sql + ") t";
        Long total = jdbcTemplate.queryForObject(countSql, Long.class, params.toArray());
        page.setTotal(total != null ? total : 0);

        // 分页
        sql.append(" LIMIT ? OFFSET ?");
        params.add(page.getSize());
        params.add((page.getCurrent() - 1) * page.getSize());

        List<Map<String, Object>> records = jdbcTemplate.queryForList(sql.toString(), params.toArray());
        page.setRecords(records);
        return page;
    }

    // ==================== 表导入 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void importTable(List<String> tableNames, String packageName, String moduleName,
                            String businessName, String functionName, String functionAuthor) {
        for (String tableName : tableNames) {
            // 检查已存在
            Long existed = genTableMapper.selectCount(
                    Wrappers.<GenTable>lambdaQuery().eq(GenTable::getTableName, tableName));
            if (existed != null && existed > 0) {
                throw new ServiceException(GeneratorErrorCode.GEN_TABLE_EXISTS);
            }

            // 查询表注释
            String commentSql = "SELECT TABLE_COMMENT AS table_comment FROM information_schema.tables " +
                    "WHERE table_schema = (SELECT DATABASE()) AND TABLE_NAME = ?";
            Map<String, Object> tableInfo = jdbcTemplate.queryForMap(commentSql, tableName);
            String tableComment = String.valueOf(tableInfo.getOrDefault("table_comment", ""));

            // 插入 gen_table
            GenTable genTable = new GenTable();
            genTable.setId(null);
            genTable.setTableName(tableName);
            genTable.setTableComment(tableComment);
            genTable.setClassName(toClassName(tableName));
            genTable.setPackageName(packageName);
            genTable.setModuleName(moduleName);
            genTable.setBusinessName(businessName);
            genTable.setFunctionName(functionName);
            genTable.setFunctionAuthor(functionAuthor);
            genTable.setGenType("0");
            genTableMapper.insert(genTable);

            // 查询字段列表
            importColumns(genTable.getId(), tableName);
        }
    }

    private void importColumns(Long tableId, String tableName) {
        String colSql = "SELECT COLUMN_NAME AS column_name, COLUMN_COMMENT AS column_comment, " +
                "COLUMN_TYPE AS column_type, DATA_TYPE AS data_type, " +
                "COLUMN_KEY AS column_key, EXTRA AS extra, " +
                "IS_NULLABLE AS is_nullable, ORDINAL_POSITION AS ordinal_position " +
                "FROM information_schema.columns " +
                "WHERE table_schema = (SELECT DATABASE()) AND TABLE_NAME = ? " +
                "ORDER BY ORDINAL_POSITION";
        List<Map<String, Object>> columns = jdbcTemplate.queryForList(colSql, tableName);

        int sort = 1;
        for (Map<String, Object> col : columns) {
            String columnName = String.valueOf(col.get("column_name"));
            String columnComment = String.valueOf(col.getOrDefault("column_comment", ""));
            String columnType = String.valueOf(col.getOrDefault("column_type", ""));
            String dataType = String.valueOf(col.getOrDefault("data_type", ""));
            String columnKey = String.valueOf(col.getOrDefault("column_key", ""));
            String extra = String.valueOf(col.getOrDefault("extra", ""));

            GenTableColumn column = new GenTableColumn();
            column.setId(null);
            column.setTableId(tableId);
            column.setColumnName(columnName);
            column.setColumnComment(columnComment);
            column.setColumnType(columnType);
            column.setJavaType(toJavaType(dataType));
            column.setJavaField(toCamelCase(columnName));
            column.setIsPk("PRI".equals(columnKey) ? 1 : 0);
            column.setIsIncrement(extra.contains("auto_increment") ? 1 : 0);
            column.setIsRequired("NO".equals(String.valueOf(col.getOrDefault("is_nullable", "YES"))) ? 1 : 0);
            column.setIsInsert(BASE_DO_FIELDS.contains(columnName) ? 0 : 1);
            column.setIsEdit("id".equals(columnName) || BASE_DO_FIELDS.contains(columnName) ? 0 : 1);
            column.setIsList("id".equals(columnName) || "deleted".equals(columnName) || "remark".equals(columnName) ? 0 : 1);
            column.setIsQuery("id".equals(columnName) || "remark".equals(columnName) || "deleted".equals(columnName) ? 0 : 1);
            column.setQueryType(inferQueryType(dataType));
            column.setHtmlType(inferHtmlType(dataType));
            column.setSort(sort++);
            genTableColumnMapper.insert(column);
        }
    }

    // ==================== 生成表管理 ====================

    @Override
    public IPage<GenTable> selectGenTableList(IPage<GenTable> page, String tableName, String tableComment) {
        LambdaQueryWrapper<GenTable> wq = Wrappers.<GenTable>lambdaQuery()
                .like(StrUtil.isNotBlank(tableName), GenTable::getTableName, tableName)
                .like(StrUtil.isNotBlank(tableComment), GenTable::getTableComment, tableComment)
                .orderByDesc(GenTable::getCreateTime);
        return genTableMapper.selectPage(page, wq);
    }

    @Override
    public GenTable selectGenTableById(Long id) {
        GenTable table = genTableMapper.selectById(id);
        if (table == null) {
            throw new ServiceException(GeneratorErrorCode.GEN_TABLE_NOT_FOUND);
        }
        return table;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteGenTable(List<Long> ids) {
        for (Long id : ids) {
            genTableColumnMapper.delete(Wrappers.<GenTableColumn>lambdaQuery()
                    .eq(GenTableColumn::getTableId, id));
            genTableMapper.deleteById(id);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void synchDb(Long tableId) {
        GenTable genTable = genTableMapper.selectById(tableId);
        if (genTable == null) {
            throw new ServiceException(GeneratorErrorCode.GEN_TABLE_NOT_FOUND);
        }
        // 删除旧字段
        genTableColumnMapper.delete(Wrappers.<GenTableColumn>lambdaQuery()
                .eq(GenTableColumn::getTableId, tableId));
        // 重新导入
        importColumns(tableId, genTable.getTableName());
    }

    @Override
    public List<GenTableColumn> selectGenTableColumnListByTableId(Long tableId) {
        return genTableColumnMapper.selectList(
                Wrappers.<GenTableColumn>lambdaQuery()
                        .eq(GenTableColumn::getTableId, tableId)
                        .orderByAsc(GenTableColumn::getSort));
    }

    @Override
    public void updateGenTableColumn(GenTableColumn column) {
        genTableColumnMapper.updateById(column);
    }

    // ==================== 代码生成 ====================

    private Map<String, Object> buildModel(Long tableId) {
        GenTable genTable = genTableMapper.selectById(tableId);
        if (genTable == null) {
            throw new ServiceException(GeneratorErrorCode.GEN_TABLE_NOT_FOUND);
        }
        List<GenTableColumn> columns = selectGenTableColumnListByTableId(tableId);

        Map<String, Object> model = new HashMap<>();
        model.put("tableComment", genTable.getTableComment());
        model.put("functionName", genTable.getFunctionName());
        model.put("className", genTable.getClassName());
        model.put("classVarName", StrUtil.lowerFirst(genTable.getClassName()));
        model.put("packageName", genTable.getPackageName());
        model.put("moduleName", genTable.getModuleName());
        model.put("businessName", genTable.getBusinessName());
        model.put("author", genTable.getFunctionAuthor());
        model.put("datetime", DateUtil.now());
        model.put("tableName", genTable.getTableName());

        // 主键字段
        model.put("pkColumn", columns.stream()
                .filter(c -> c.getIsPk() == 1).findFirst().orElse(null));

        // 列表字段（用于表格/分页查询）
        model.put("listColumns", columns.stream()
                .filter(c -> c.getIsList() == 1).toList());

        // 所有字段
        model.put("columns", columns);

        // 查询字段
        model.put("queryColumns", columns.stream()
                .filter(c -> c.getIsQuery() == 1).toList());

        // 新增字段
        model.put("insertColumns", columns.stream()
                .filter(c -> c.getIsInsert() == 1).toList());

        // 编辑字段
        model.put("editColumns", columns.stream()
                .filter(c -> c.getIsEdit() == 1).toList());

        // 需要 import 的类型
        Set<String> importTypes = new LinkedHashSet<>();
        for (GenTableColumn col : columns) {
            if (col.getJavaType() != null && IMPORTABLE_TYPES.contains(col.getJavaType())) {
                importTypes.add(col.getJavaType());
            }
        }
        model.put("importTypes", importTypes);
        model.put("hasImportableTypes", !importTypes.isEmpty());

        // 前端权限前缀、API 前缀
        String permPrefix = genTable.getModuleName() + ":" + genTable.getBusinessName();
        String apiPrefix = "/" + genTable.getModuleName() + "/" + genTable.getBusinessName();
        model.put("permPrefix", permPrefix);
        model.put("apiPrefix", apiPrefix);

        // 每个字段的 TypeScript 类型
        Map<String, String> tsTypeMap = new HashMap<>();
        for (GenTableColumn col : columns) {
            String tsType = TS_TYPE_MAP.getOrDefault(col.getJavaType(), "any");
            tsTypeMap.put(col.getJavaField(), tsType);
        }
        model.put("tsTypeMap", tsTypeMap);

        // TS 默认值映射
        Map<String, String> tsDefaultMap = new HashMap<>();
        tsDefaultMap.put("string", "''");
        tsDefaultMap.put("number", "undefined");
        tsDefaultMap.put("boolean", "false");
        model.put("tsDefaultMap", tsDefaultMap);

        return model;
    }

    @Override
    public Map<String, String> previewCode(Long tableId) {
        Map<String, Object> model = buildModel(tableId);
        GenTable table = (GenTable) genTableMapper.selectById(tableId);

        String javaDir = toModulePath(table.getPackageName()) + "/src/main/java/"
                + table.getPackageName().replace(".", "/");

        Map<String, String> result = new LinkedHashMap<>();
        try {
            // 后端模板
            result.put(javaDir + "/domain/entity/" + table.getClassName() + ".java",
                    render("domain.ftl", model));
            result.put(javaDir + "/mapper/" + table.getClassName() + "Mapper.java",
                    render("mapper.ftl", model));
            result.put(javaDir + "/service/" + table.getClassName() + "Service.java",
                    render("service.ftl", model));
            result.put(javaDir + "/service/impl/" + table.getClassName() + "ServiceImpl.java",
                    render("serviceImpl.ftl", model));
            result.put(javaDir + "/controller/" + table.getClassName() + "Controller.java",
                    render("controller.ftl", model));
            result.put("sql/" + toFlywayFileName(table.getTableName()) + ".sql",
                    render("flyway.ftl", model));

            // 前端 PC 模板
            String feDir = "pivotos-ui/apps/admin/src";
            result.put(feDir + "/api/" + table.getModuleName() + "/" + table.getBusinessName() + ".ts",
                    render("pc-api.ftl", model));
            result.put(feDir + "/views/" + table.getModuleName() + "/" + table.getBusinessName() + "/index.vue",
                    render("pc-page.ftl", model));

            // 前端 uni-app 模板
            String appDir = "pivotos-app/src";
            result.put(appDir + "/api/" + table.getModuleName() + "/" + table.getBusinessName() + ".ts",
                    render("uni-api.ftl", model));
            result.put(appDir + "/pages-gen/" + table.getModuleName() + "/" + table.getBusinessName() + "/list.vue",
                    render("uni-list.ftl", model));
            result.put(appDir + "/pages-gen/" + table.getModuleName() + "/" + table.getBusinessName() + "/form.vue",
                    render("uni-form.ftl", model));
            result.put(appDir + "/pages-gen/" + table.getModuleName() + "/" + table.getBusinessName() + "/detail.vue",
                    render("uni-detail.ftl", model));
        } catch (Exception e) {
            log.error("代码预览失败", e);
            throw new ServiceException(GeneratorErrorCode.GEN_TEMPLATE_RENDER_FAILED);
        }
        return result;
    }

    @Override
    public byte[] downloadCode(Long tableId) {
        Map<String, String> codeMap = previewCode(tableId);
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> entry : codeMap.entrySet()) {
                zos.putNextEntry(new ZipEntry(entry.getKey()));
                zos.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
            zos.finish();
            return bos.toByteArray();
        } catch (Exception e) {
            log.error("代码打包下载失败", e);
            throw new ServiceException(GeneratorErrorCode.GEN_CODE_FAILED);
        }
    }

    @Override
    public void generateToProject(Long tableId) {
        GenTable table = genTableMapper.selectById(tableId);
        String genPath = StrUtil.isNotBlank(table.getGenPath())
                ? table.getGenPath()
                : System.getProperty("user.dir");

        Map<String, String> codeMap = previewCode(tableId);
        for (Map.Entry<String, String> entry : codeMap.entrySet()) {
            java.io.File file = new java.io.File(genPath, entry.getKey());
            if (!file.getParentFile().exists()) {
                file.getParentFile().mkdirs();
            }
            FileUtil.writeString(entry.getValue(), file, StandardCharsets.UTF_8);
        }
        log.info("代码已生成到路径: {}", genPath);
    }

    // ==================== 工具方法 ====================

    /** FreeMarker 模板渲染 */
    private String render(String templateName, Map<String, Object> model) throws Exception {
        Template template = freemarkerConfig.getTemplate(templateName);
        StringWriter writer = new StringWriter();
        template.process(model, writer);
        return writer.toString();
    }

    /** 表名 → 类名（sys_user → SysUser） */
    private String toClassName(String tableName) {
        String[] parts = tableName.split("_");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part.length() > 0) {
                sb.append(Character.toUpperCase(part.charAt(0)));
                if (part.length() > 1) {
                    sb.append(part.substring(1));
                }
            }
        }
        return sb.toString();
    }

    /** 列名 → 驼峰（user_name → userName） */
    private String toCamelCase(String columnName) {
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (char c : columnName.toCharArray()) {
            if (c == '_') {
                upper = true;
            } else {
                sb.append(upper ? Character.toUpperCase(c) : c);
                upper = false;
            }
        }
        return sb.toString();
    }

    /** 数据库类型 → Java 类型 */
    private String toJavaType(String dataType) {
        if (dataType == null) return "String";
        String key = dataType.toLowerCase().replaceAll("\\(.*\\)", "").trim();
        return JAVA_TYPE_MAP.getOrDefault(key, "String");
    }

    /** 推断查询方式 */
    private String inferQueryType(String dataType) {
        if (dataType == null) return "EQ";
        String type = dataType.toLowerCase();
        if (type.contains("datetime") || type.contains("timestamp") || type.contains("date")) {
            return "BETWEEN";
        }
        if (type.contains("varchar") || type.contains("text") || type.contains("char")) {
            return "LIKE";
        }
        return "EQ";
    }

    /** 推断 HTML 显示类型 */
    private String inferHtmlType(String dataType) {
        if (dataType == null) return "input";
        String type = dataType.toLowerCase();
        if (type.contains("text") || type.contains("longtext")) return "textarea";
        if (type.contains("datetime") || type.contains("timestamp") || type.contains("date") || type.contains("time")) {
            return "datetime";
        }
        return "input";
    }

    /** 包名 → 模块目录路径 */
    private String toModulePath(String packageName) {
        // com.pivotos.system → pivotos-plugins/pivotos-plugin-system
        if (packageName.contains("system")) return "pivotos-plugins/pivotos-plugin-system";
        if (packageName.contains("message")) return "pivotos-plugins/pivotos-plugin-message";
        if (packageName.contains("file")) return "pivotos-plugins/pivotos-plugin-file";
        if (packageName.contains("ai")) return "pivotos-plugins/pivotos-plugin-ai";
        return "pivotos-plugins/pivotos-plugin-" + StrUtil.subAfter(packageName, ".", true);
    }

    /** 表名 → Flyway 文件名 */
    private String toFlywayFileName(String tableName) {
        return "V" + DateUtil.format(DateUtil.date(), "yyyyMMdd_HHmmss") + "__gen_" + tableName;
    }
}
