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

    /** DTO 需要跳过的字段（BaseDO / BaseQuery 已有）；存驼峰形，比较前先 toCamelCase（S47 修复：原直接拿 snake 列名比较永不命中） */
    private static final Set<String> BASE_DO_FIELDS = Set.of(
            "id", "createBy", "createTime", "updateBy", "updateTime", "deleted", "tenantId"
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
            // S47：审计/租户字段（驼峰比较）默认不进 insert/edit/list/query——
            // 否则 pc-api/uni-api 模板硬编码的 createTime 与 listColumns 重复，typecheck 直接钉死
            boolean baseField = BASE_DO_FIELDS.contains(toCamelCase(columnName));
            column.setIsInsert(baseField ? 0 : 1);
            column.setIsEdit("id".equals(columnName) || baseField ? 0 : 1);
            column.setIsList("id".equals(columnName) || "remark".equals(columnName) || baseField ? 0 : 1);
            column.setIsQuery("id".equals(columnName) || "remark".equals(columnName) || baseField ? 0 : 1);
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
        // S50（2.4-F1）：fk 关联配置标识符白名单校验——这些值会进入生成代码的 SQL 常量
        validateIdentifier(column.getFkTable(), "关联表名");
        validateIdentifier(column.getFkValueColumn(), "关联值列");
        validateIdentifier(column.getFkLabelColumn(), "关联显示列");
        genTableColumnMapper.updateById(column);
    }

    @Override
    public void updateGenTable(GenTable table) {
        if (table.getId() == null || genTableMapper.selectById(table.getId()) == null) {
            throw new ServiceException(GeneratorErrorCode.GEN_TABLE_NOT_FOUND);
        }
        String tpl = table.getTplCategory();
        if (tpl == null || tpl.isBlank()) {
            table.setTplCategory("crud");
        } else if (!Set.of("crud", "tree", "sub").contains(tpl)) {
            throw new ServiceException(GeneratorErrorCode.GEN_PARAM_INVALID);
        }
        // 树/主子配置字段同样进生成代码，做标识符白名单校验
        validateIdentifier(table.getTreeCode(), "树编码字段");
        validateIdentifier(table.getTreeParentCode(), "树父编码字段");
        validateIdentifier(table.getTreeName(), "树名称字段");
        validateIdentifier(table.getSubTableName(), "子表名");
        validateIdentifier(table.getSubTableFkName(), "子表外键列名");
        // S51（2.4-F3）：主子模板必填子表名 + 外键列
        if ("sub".equals(table.getTplCategory())
                && (table.getSubTableName() == null || table.getSubTableName().isBlank()
                || table.getSubTableFkName() == null || table.getSubTableFkName().isBlank())) {
            throw new ServiceException(GeneratorErrorCode.GEN_SUB_TABLE_NOT_FOUND);
        }
        genTableMapper.updateById(table);
    }

    /** fk 目标表是否含 deleted 逻辑删除列（生成期一次性判定，查不到按无处理） */
    private boolean fkTableHasDeletedColumn(String fkTable) {
        try {
            Long cnt = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                    "WHERE table_schema = (SELECT DATABASE()) AND TABLE_NAME = ? AND COLUMN_NAME = 'deleted'",
                    Long.class, fkTable);
            return cnt != null && cnt > 0;
        } catch (Exception e) {
            log.warn("[Generator] fk 目标表 deleted 列探测失败，按无处理: {}", fkTable, e);
            return false;
        }
    }

    /** 标识符白名单（null/空串放行=未配置；非空必须是小写 SQL 标识符，防配置值注入生成代码） */
    private static void validateIdentifier(String value, String label) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!value.matches("^[a-z][a-z0-9_]{0,63}$")) {
            log.warn("[Generator] {}非法: {}", label, value);
            throw new ServiceException(GeneratorErrorCode.GEN_PARAM_INVALID);
        }
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

        // S50（2.4-F1）：模板类型 + fk 关联字段（fkTable 非空即关联下拉列）
        model.put("tplCategory", genTable.getTplCategory() == null ? "crud" : genTable.getTplCategory());
        List<GenTableColumn> fkColumns = columns.stream()
                .filter(c -> c.getFkTable() != null && !c.getFkTable().isBlank()
                        && c.getFkValueColumn() != null && !c.getFkValueColumn().isBlank()
                        && c.getFkLabelColumn() != null && !c.getFkLabelColumn().isBlank())
                .toList();
        model.put("fkColumns", fkColumns);
        model.put("hasFk", !fkColumns.isEmpty());
        // fk 目标表带逻辑删除列的字段集（生成期判定，选项/翻译 SQL 过滤 deleted=0；
        // 用 List 而非 Set——FreeMarker ?seq_contains 只认序列）
        List<String> fkDeletedFields = new ArrayList<>();
        for (GenTableColumn c : fkColumns) {
            if (fkTableHasDeletedColumn(c.getFkTable())) {
                fkDeletedFields.add(c.getJavaField());
            }
        }
        model.put("fkDeletedFields", fkDeletedFields);

        // S51（2.4-F3）：主子表——tpl_category=sub 时加载子表 gen 记录并构建子模型
        boolean isSub = "sub".equals(genTable.getTplCategory());
        model.put("hasSub", isSub);
        if (isSub) {
            buildSubModel(genTable, model);
        }

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

        // 全限定 import 路径（dto/vo 模板用）
        Set<String> importPaths = new LinkedHashSet<>();
        for (String t : importTypes) {
            switch (t) {
                case "BigDecimal" -> importPaths.add("java.math.BigDecimal");
                case "LocalDateTime" -> importPaths.add("java.time.LocalDateTime");
                case "LocalDate" -> importPaths.add("java.time.LocalDate");
                case "LocalTime" -> importPaths.add("java.time.LocalTime");
                default -> { }
            }
        }
        model.put("importPaths", importPaths);

        // VO 字段（BaseDTO 已有 id/审计字段，剔除）
        Set<String> baseDtoFields = Set.of("id", "createBy", "createTime", "updateBy", "updateTime", "deleted");
        model.put("voColumns", columns.stream()
                .filter(c -> !baseDtoFields.contains(c.getJavaField())).toList());

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

    /**
     * 主子表子模型（S51 / 2.4-F3）。
     * 子表须已导入生成器（独立 gen_table 行）；fk 列由后端按主表 id 回写，
     * 不进表单/DTO/VO；子实体/子 VO 渲染复用 domain.ftl / vo.ftl（subModel 键名对齐主模型）。
     */
    private void buildSubModel(GenTable genTable, Map<String, Object> model) {
        String subTableName = genTable.getSubTableName();
        String subFkName = genTable.getSubTableFkName();
        if (subTableName == null || subTableName.isBlank() || subFkName == null || subFkName.isBlank()) {
            throw new ServiceException(GeneratorErrorCode.GEN_SUB_TABLE_NOT_FOUND);
        }
        GenTable subTable = genTableMapper.selectOne(
                Wrappers.<GenTable>lambdaQuery().eq(GenTable::getTableName, subTableName));
        if (subTable == null) {
            log.warn("[Generator] 子表未导入生成器: {}", subTableName);
            throw new ServiceException(GeneratorErrorCode.GEN_SUB_TABLE_NOT_FOUND);
        }
        List<GenTableColumn> subColumns = selectGenTableColumnListByTableId(subTable.getId());
        String subFkField = toCamelCase(subFkName);
        boolean fkExists = subColumns.stream().anyMatch(c -> subFkName.equals(c.getColumnName()));
        if (!fkExists) {
            log.warn("[Generator] 子表外键列不存在: {}.{}", subTableName, subFkName);
            throw new ServiceException(GeneratorErrorCode.GEN_SUB_TABLE_NOT_FOUND);
        }

        model.put("subClassName", subTable.getClassName());
        model.put("subClassVarName", StrUtil.lowerFirst(subTable.getClassName()));
        model.put("subFunctionName", subTable.getFunctionName());
        model.put("subTableName", subTable.getTableName());
        model.put("subTableComment", subTable.getTableComment());
        model.put("subFkField", subFkField);
        model.put("subFkColumn", subFkName);
        model.put("subColumns", subColumns);
        // 子表内嵌编辑录入列 / 展示列 / VO 列均剔除 fk 与审计字段
        model.put("subInsertColumns", subColumns.stream()
                .filter(c -> c.getIsInsert() == 1 && !subFkField.equals(c.getJavaField()))
                .filter(c -> !BASE_DO_FIELDS.contains(c.getJavaField()) && !"id".equals(c.getJavaField()))
                .toList());
        model.put("subListColumns", subColumns.stream()
                .filter(c -> c.getIsList() == 1 && !subFkField.equals(c.getJavaField()))
                .toList());
        Set<String> subBase = Set.of("id", "createBy", "createTime", "updateBy", "updateTime", "deleted");
        model.put("subVoColumns", subColumns.stream()
                .filter(c -> !subBase.contains(c.getJavaField()) && !subFkField.equals(c.getJavaField()))
                .toList());

        // 子表需要 import 的类型（与主模型同映射）
        Set<String> subImportTypes = new LinkedHashSet<>();
        for (GenTableColumn col : subColumns) {
            if (col.getJavaType() != null && IMPORTABLE_TYPES.contains(col.getJavaType())) {
                subImportTypes.add(col.getJavaType());
            }
        }
        Set<String> subImportPaths = new LinkedHashSet<>();
        for (String t : subImportTypes) {
            switch (t) {
                case "BigDecimal" -> subImportPaths.add("java.math.BigDecimal");
                case "LocalDateTime" -> subImportPaths.add("java.time.LocalDateTime");
                case "LocalDate" -> subImportPaths.add("java.time.LocalDate");
                case "LocalTime" -> subImportPaths.add("java.time.LocalTime");
                default -> { }
            }
        }
        model.put("subImportPaths", subImportPaths);

        // 子实体/子 VO 复用 domain.ftl / vo.ftl 的子模型（键名对齐主模型）
        Map<String, Object> subModel = new HashMap<>();
        subModel.put("packageName", genTable.getPackageName());
        subModel.put("functionName", subTable.getFunctionName());
        subModel.put("className", subTable.getClassName());
        subModel.put("tableName", subTable.getTableName());
        subModel.put("author", genTable.getFunctionAuthor());
        subModel.put("datetime", model.get("datetime"));
        subModel.put("columns", subColumns);
        subModel.put("pkColumn", subColumns.stream()
                .filter(c -> c.getIsPk() == 1).findFirst().orElse(null));
        subModel.put("importTypes", subImportTypes);
        subModel.put("hasImportableTypes", !subImportTypes.isEmpty());
        subModel.put("importPaths", subImportPaths);
        subModel.put("voColumns", model.get("subVoColumns"));
        model.put("subModel", subModel);
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
                    Boolean.TRUE.equals(model.get("hasSub"))
                            ? render("serviceImpl-sub.ftl", model)
                            : render("serviceImpl.ftl", model));
            result.put(javaDir + "/domain/dto/" + table.getClassName() + "CreateRequest.java",
                    render("dto-create.ftl", model));
            result.put(javaDir + "/domain/dto/" + table.getClassName() + "UpdateRequest.java",
                    render("dto-update.ftl", model));
            result.put(javaDir + "/domain/dto/" + table.getClassName() + "QueryRequest.java",
                    render("dto-query.ftl", model));
            result.put(javaDir + "/domain/vo/" + table.getClassName() + "VO.java",
                    render("vo.ftl", model));
            result.put(javaDir + "/controller/" + table.getClassName() + "Controller.java",
                    render("controller.ftl", model));
            // S47（2.3-F3）：app 侧并行端点组（app-user/wx-mini-user 登录即可），sys 端点组不动
            result.put(javaDir + "/controller/" + table.getClassName() + "AppController.java",
                    render("app-controller.ftl", model));
            result.put("sql/" + toFlywayFileName(table.getTableName()) + ".sql",
                    render("flyway.ftl", model));

            // 前端 PC 模板
            String feDir = "pivotos-ui/apps/admin/src";
            result.put(feDir + "/api/" + table.getModuleName() + "/" + table.getBusinessName() + ".ts",
                    render("pc-api.ftl", model));
            result.put(feDir + "/views/" + table.getModuleName() + "/" + table.getBusinessName() + "/index.vue",
                    Boolean.TRUE.equals(model.get("hasSub"))
                            ? render("pc-page-sub.ftl", model)
                            : render("pc-page.ftl", model));

            // 前端 uni-app 模板
            String appDir = "pivotos-app/src";
            result.put(appDir + "/api/" + table.getModuleName() + "/" + table.getBusinessName() + ".ts",
                    render("uni-api.ftl", model));
            result.put(appDir + "/pages-gen/" + table.getModuleName() + "/" + table.getBusinessName() + "/list.vue",
                    render("uni-list.ftl", model));
            result.put(appDir + "/pages-gen/" + table.getModuleName() + "/" + table.getBusinessName() + "/form.vue",
                    Boolean.TRUE.equals(model.get("hasSub"))
                            ? render("uni-form-sub.ftl", model)
                            : render("uni-form.ftl", model));
            result.put(appDir + "/pages-gen/" + table.getModuleName() + "/" + table.getBusinessName() + "/detail.vue",
                    render("uni-detail.ftl", model));

            // S51（2.4-F3）：子表产物——子实体/子 Mapper 复用 domain.ftl/mapper.ftl + 子 VO + 子项 DTO
            if (Boolean.TRUE.equals(model.get("hasSub"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> subModel = (Map<String, Object>) model.get("subModel");
                String subClassName = String.valueOf(model.get("subClassName"));
                result.put(javaDir + "/domain/entity/" + subClassName + ".java",
                        render("domain.ftl", subModel));
                result.put(javaDir + "/mapper/" + subClassName + "Mapper.java",
                        render("mapper.ftl", subModel));
                result.put(javaDir + "/domain/vo/" + subClassName + "VO.java",
                        render("vo.ftl", subModel));
                result.put(javaDir + "/domain/dto/" + subClassName + "ItemRequest.java",
                        render("sub-item-dto.ftl", model));
            }
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

    // ==================== Plugin 骨架（S42 / 2.2-F12） ====================

    /**
     * 渲染 Plugin 双模块骨架（不碰 gen_table）。
     * params: pluginName, className, displayName, tablePrefix, moduleDesc, errorCodeBase(int)
     */
    public Map<String, String> previewPluginSkeleton(Map<String, Object> params) {
        String pluginName = String.valueOf(params.get("pluginName"));
        String className = String.valueOf(params.get("className"));
        String base = "pivotos-plugins/pivotos-plugin-" + pluginName;
        String apiBase = base + "-api";
        String apiJava = apiBase + "/src/main/java/com/pivotos/" + pluginName + "/api";
        String implJava = base + "/src/main/java/com/pivotos/" + pluginName;

        Map<String, Object> model = new HashMap<>(params);
        model.put("projectVersion", resolveProjectVersion());

        Map<String, String> result = new LinkedHashMap<>();
        try {
            result.put(apiBase + "/pom.xml", render("plugin-api-pom.ftl", model));
            result.put(base + "/pom.xml", render("plugin-pom.ftl", model));
            result.put(apiJava + "/constant/" + className + "ErrorCode.java",
                    render("plugin-errorcode.ftl", model));
            result.put(apiJava + "/facade/I" + className + "Facade.java",
                    render("plugin-facade.ftl", model));
            result.put(implJava + "/package-info.java", render("plugin-package-info.ftl", model));
            result.put(base + "/src/main/resources/db/migration/README.md",
                    render("plugin-flyway-readme.ftl", model));
            result.put(base + "/docs/menu.sql.template", render("plugin-menu-sql.ftl", model));
        } catch (Exception e) {
            log.error("Plugin 骨架渲染失败: pluginName={}", pluginName, e);
            throw new ServiceException(GeneratorErrorCode.GEN_TEMPLATE_RENDER_FAILED);
        }
        return result;
    }

    /** 当前工程版本（读自身 jar 的 pom.properties，供骨架 pom parent version；dev 环境回落 2.1.0） */
    private String resolveProjectVersion() {
        try (var in = getClass().getClassLoader().getResourceAsStream(
                "META-INF/maven/com.pivotos/pivotos-plugin-generator/pom.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                String v = props.getProperty("version");
                if (v != null && !v.isBlank()) {
                    return v;
                }
            }
        } catch (Exception e) {
            log.warn("读取工程版本失败，回落默认值", e);
        }
        return "2.1.0";
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
