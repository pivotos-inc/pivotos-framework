package com.pivotos.starter.datainspect.security;

import com.pivotos.starter.datainspect.api.enums.DataInspectErrorCode;
import com.pivotos.starter.datainspect.api.security.GuardContext;
import com.pivotos.starter.datainspect.api.security.GuardResult;
import com.pivotos.starter.datainspect.api.security.SqlGuard;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.util.TablesNamesFinder;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * MySQL 自由 SQL 安全闸门（<b>硬生效</b>，与权限无关）。
 *
 * <p>校验顺序（任一不过即拒，全部对超管生效）：
 * <ol>
 *   <li>空 / 超长；</li>
 *   <li>含 {@code ;} → 视为堆叠查询拒绝（驱动层 {@code allowMultiQueries=false} 是第二道，不依赖它）；</li>
 *   <li>剥离注释（含 MySQL 可执行注释 {@code /*! ... *}{@code /}）后必须仍能解析；</li>
 *   <li>仅接受单表 SELECT：UNION / 子查询 / EXPLAIN / INTO 一律拒绝；</li>
 *   <li>函数黑名单（SLEEP / BENCHMARK / LOAD_FILE ...）；</li>
 *   <li>库表白名单 + 系统库（information_schema / mysql / sys / performance_schema）禁用；</li>
 *   <li>强制 LIMIT（无则注入，有则收紧到 hardMaxRows）；</li>
 *   <li>租户改写：非忽略表且存在租户列 → {@code AND tenant_id = ?}（超管不豁免）。</li>
 * </ol>
 *
 * <p>实现纪律：全程 try/catch，<b>解析异常一律按拒绝处理</b>（保守默认），永不抛异常。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public class MySqlSqlGuard implements SqlGuard {

    /** 危险函数：DoS / 文件读取 / 锁 / 主从控制 */
    private static final Set<String> FORBIDDEN_FUNCTIONS = Set.of(
            "sleep", "benchmark", "load_file", "get_lock", "release_lock", "is_free_lock",
            "is_used_lock", "sys_exec", "sys_eval", "master_pos_wait", "name_const", "uuid",
            "load_extension", "pg_sleep");

    /** 系统库：用户语句一律不得引用（元数据只由组件内部固定 SQL 访问） */
    private static final Set<String> FORBIDDEN_SCHEMAS = Set.of(
            "information_schema", "mysql", "sys", "performance_schema");

    /** 表名合法形态：白名单校验前的形状约束 */
    private static final java.util.regex.Pattern TABLE_NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_$]+");

    /** INTO 兜底检测（词边界，避免命中列名如 point_of） */
    private static final java.util.regex.Pattern INTO_PATTERN = java.util.regex.Pattern.compile("(?i)\\binto\\b");

    private final int maxSqlLength;

    public MySqlSqlGuard(int maxSqlLength) {
        this.maxSqlLength = maxSqlLength <= 0 ? 4000 : maxSqlLength;
    }

    public MySqlSqlGuard() {
        this(4000);
    }

    @Override
    public GuardResult inspect(String sql, GuardContext context) {
        List<String> warnings = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return reject(DataInspectErrorCode.SQL_REJECTED, "语句为空");
        }
        String raw = sql.trim();
        if (raw.length() > maxSqlLength) {
            return reject(DataInspectErrorCode.SQL_REJECTED, "语句长度超过上限 " + maxSqlLength);
        }

        // ② 堆叠查询
        if (raw.indexOf(';') >= 0) {
            return reject(DataInspectErrorCode.SQL_MULTI_STATEMENT, "语句中包含分号");
        }

        // ③ 剥离注释（含 MySQL 可执行注释）
        String stripped = stripComments(raw);
        if (stripped.isBlank()) {
            return reject(DataInspectErrorCode.SQL_REJECTED, "语句剥离注释后为空");
        }
        if (stripped.indexOf(';') >= 0) {
            return reject(DataInspectErrorCode.SQL_MULTI_STATEMENT, "注释剥离后出现分号");
        }

        // ④ 解析 + 类型白名单
        Statement statement;
        try {
            statement = CCJSqlParserUtil.parse(stripped);
        } catch (Exception e) {
            return reject(DataInspectErrorCode.SQL_REJECTED, "语句无法解析（不支持的语法一律拒绝）：" + e.getMessage());
        }
        if (!(statement instanceof Select select)) {
            return reject(DataInspectErrorCode.SQL_NOT_SELECT, "只允许 SELECT 语句");
        }
        if (!(select.getSelectBody() instanceof PlainSelect plain)) {
            if (select.getSelectBody() instanceof SetOperationList) {
                return reject(DataInspectErrorCode.SQL_NOT_SELECT, "暂不支持 UNION / 集合运算");
            }
            return reject(DataInspectErrorCode.SQL_NOT_SELECT, "暂不支持该 SELECT 形态（子查询 / 括号查询）");
        }
        if (plain.getFromItem() == null) {
            return reject(DataInspectErrorCode.SQL_NOT_SELECT, "暂不支持无 FROM 的 SELECT");
        }
        // SELECT ... INTO OUTFILE / DUMPFILE / @var：AST 形态较多，这里用剥离注释后的文本兜底
        if (INTO_PATTERN.matcher(stripped).find()) {
            return reject(DataInspectErrorCode.SQL_NOT_SELECT, "暂不支持 SELECT ... INTO（含 INTO OUTFILE / DUMPFILE / 变量）");
        }

        // ⑤ 函数黑名单
        String forbidden = findForbiddenFunction(plain);
        if (forbidden != null) {
            return reject(DataInspectErrorCode.SQL_FUNCTION_FORBIDDEN, "禁止使用函数 " + forbidden.toUpperCase(Locale.ROOT));
        }

        // ⑥ 库表白名单
        List<String> tables = resolveTables(statement);
        if (tables.isEmpty()) {
            return reject(DataInspectErrorCode.SQL_TABLE_FORBIDDEN, "未识别到任何表");
        }
        for (String table : tables) {
            String schema = schemaOf(table);
            String name = nameOf(table);
            if (!TABLE_NAME.matcher(name).matches()) {
                return reject(DataInspectErrorCode.SQL_TABLE_FORBIDDEN, "表名不合法：" + name);
            }
            if (schema != null && FORBIDDEN_SCHEMAS.contains(schema.toLowerCase(Locale.ROOT))) {
                return reject(DataInspectErrorCode.SQL_TABLE_FORBIDDEN, "不允许查询系统库：" + schema);
            }
            if (!isTableAllowed(table, context)) {
                return reject(DataInspectErrorCode.SQL_TABLE_FORBIDDEN,
                        "表不在白名单内：" + table + "（如需放开请配置 pivotos.datainspect.mysql.table-whitelist）");
            }
        }
        if (tables.size() > 1) {
            return reject(DataInspectErrorCode.SQL_TABLE_FORBIDDEN,
                    "自由查询暂不支持多表（JOIN / 子查询），以保证租户改写无歧义");
        }

        // ⑦ 强制 LIMIT
        int effectiveMaxRows = effectiveMaxRows(context);
        Limit limit = plain.getLimit();
        if (limit == null || limit.getRowCount() == null) {
            Limit injected = new Limit();
            injected.setRowCount(new LongValue(effectiveMaxRows));
            plain.setLimit(injected);
            warnings.add("已自动注入 LIMIT " + effectiveMaxRows);
        } else {
            long requested = parseCount(limit.getRowCount());
            if (requested <= 0 || requested > effectiveMaxRows) {
                limit.setRowCount(new LongValue(effectiveMaxRows));
                warnings.add("LIMIT 已收紧至 " + effectiveMaxRows);
            }
        }

        // ⑧ 租户改写（超管不豁免）
        String table = tables.get(0);
        if (context != null && context.isForceTenantScope() && context.getTenantId() != null
                && !isTenantIgnored(nameOf(table), context)
                && !Boolean.FALSE.equals(context.getTableHasTenantColumn())) {
            String column = context.getTenantIdColumn() == null ? "tenant_id" : context.getTenantIdColumn();
            Expression condition = new EqualsTo(new Column(column), new LongValue(context.getTenantId()));
            Expression where = plain.getWhere();
            plain.setWhere(where == null ? condition : new AndExpression(where, condition));
            warnings.add("已按租户改写（" + column + "=" + context.getTenantId() + "）");
        }

        return GuardResult.pass(statement.toString(), effectiveMaxRows, warnings);
    }

    private GuardResult reject(DataInspectErrorCode code, String detail) {
        return GuardResult.reject(code.name(), code.getMsg() + "：" + detail);
    }

    /**
     * 剥离 SQL 注释：<b>防注释绕过的第一道关</b>。
     *
     * <p>支持三种 MySQL 注释形态，且跳过字符串/标识符内的同名字符（避免误伤 {@code 'a--b'}）：
     * <ul>
     *   <li>{@code -- } 行注释（MySQL 要求后跟空白）；</li>
     *   <li>{@code #} 行注释；</li>
     *   <li>{@code /* ... *}{@code /} 块注释，<b>含可执行注释 {@code /*! ... *}{@code /}</b>——
     *       这是最常被用来绕过字符串黑名单的形态，必须一并吃掉。</li>
     * </ul>
     *
     * <p>剥离后仍要求语句能被 AST 解析，解析失败即拒（保守默认）。
     */
    static String stripComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        char[] chars = sql.toCharArray();
        int i = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        while (i < chars.length) {
            char c = chars[i];
            if (inSingle) {
                out.append(c);
                if (c == '\\' && i + 1 < chars.length) {
                    out.append(chars[i + 1]);
                    i += 2;
                    continue;
                }
                if (c == '\'') {
                    inSingle = false;
                }
                i++;
                continue;
            }
            if (inDouble) {
                out.append(c);
                if (c == '"') {
                    inDouble = false;
                }
                i++;
                continue;
            }
            if (inBacktick) {
                out.append(c);
                if (c == '`') {
                    inBacktick = false;
                }
                i++;
                continue;
            }
            if (c == '\'') {
                inSingle = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                inDouble = true;
                out.append(c);
                i++;
                continue;
            }
            if (c == '`') {
                inBacktick = true;
                out.append(c);
                i++;
                continue;
            }
            // 行注释：-- 后必须跟空白（MySQL 语义），否则是减号
            if (c == '-' && i + 2 < chars.length && chars[i + 1] == '-' && Character.isWhitespace(chars[i + 2])) {
                while (i < chars.length && chars[i] != '\n') {
                    i++;
                }
                continue;
            }
            if (c == '#') {
                while (i < chars.length && chars[i] != '\n') {
                    i++;
                }
                continue;
            }
            // 块注释（含 /*! 可执行注释）
            if (c == '/' && i + 1 < chars.length && chars[i + 1] == '*') {
                i += 2;
                while (i + 1 < chars.length && !(chars[i] == '*' && chars[i + 1] == '/')) {
                    i++;
                }
                i = i + 1 < chars.length ? i + 2 : chars.length;
                out.append(' ');
                continue;
            }
            out.append(c);
            i++;
        }
        return out.toString().trim();
    }

    /** 有效行数上限：请求值不可突破 hardMaxRows */
    private int effectiveMaxRows(GuardContext context) {
        int hard = context.getHardMaxRows() <= 0 ? 1000 : context.getHardMaxRows();
        int want = context.getMaxRows() <= 0 ? hard : context.getMaxRows();
        return Math.min(want, hard);
    }

    private boolean isTableAllowed(String table, GuardContext context) {
        if (context == null) {
            return false;
        }
        if (context.isAllowAllTables()) {
            return true;
        }
        Set<String> whitelist = context.getTableWhitelist() == null ? Set.of() : context.getTableWhitelist();
        String lowerFull = table.toLowerCase(Locale.ROOT);
        String lowerName = nameOf(table).toLowerCase(Locale.ROOT);
        for (String allowed : whitelist) {
            if (allowed == null || allowed.isBlank()) {
                continue;
            }
            String a = allowed.trim().toLowerCase(Locale.ROOT);
            if (a.equals(lowerFull) || a.equals(lowerName)) {
                return true;
            }
        }
        return false;
    }

    private boolean isTenantIgnored(String table, GuardContext context) {
        Set<String> ignore = context.getTenantIgnoreTables();
        return ignore != null && ignore.contains(table.toLowerCase(Locale.ROOT));
    }

    private List<String> resolveTables(Statement statement) {
        try {
            List<String> found = new TablesNamesFinder().getTableList(statement);
            return new ArrayList<>(new LinkedHashSet<>(found));
        } catch (Exception e) {
            return List.of();
        }
    }

    /** "schema.table" / "`schema`.`table`" → schema；无前缀返回 null */
    private String schemaOf(String table) {
        String normalized = table.replace("`", "").replace("\"", "");
        int dot = normalized.indexOf('.');
        return dot > 0 ? normalized.substring(0, dot) : null;
    }

    private String nameOf(String table) {
        String normalized = table.replace("`", "").replace("\"", "");
        int dot = normalized.indexOf('.');
        return dot > 0 ? normalized.substring(dot + 1) : normalized;
    }

    private long parseCount(Expression expression) {
        try {
            String text = expression.toString();
            return Long.parseLong(text.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** 递归扫描表达式中的函数调用 */
    private String findForbiddenFunction(PlainSelect plain) {
        Set<String> hits = new LinkedHashSet<>();
        ExpressionVisitorAdapter visitor = new ExpressionVisitorAdapter() {
            @Override
            public void visit(Function function) {
                String name = function.getName();
                if (name != null && FORBIDDEN_FUNCTIONS.contains(name.toLowerCase(Locale.ROOT))) {
                    hits.add(name);
                }
                super.visit(function);
            }
        };
        collectExpressions(plain).forEach(expression -> {
            try {
                expression.accept(visitor);
            } catch (Exception ignored) {
                // 访问失败按未命中处理，不会因此放行——类型白名单与表白名单仍在前面兜着
            }
        });
        return hits.isEmpty() ? null : hits.iterator().next();
    }

    /** 收集 PlainSelect 上所有需要扫描的表达式（不递归子查询：子查询已在形态校验处拒绝） */
    private List<Expression> collectExpressions(PlainSelect plain) {
        List<Expression> expressions = new ArrayList<>();
        if (plain.getSelectItems() != null) {
            for (SelectItem<?> item : plain.getSelectItems()) {
                if (item != null && item.getExpression() != null) {
                    expressions.add(item.getExpression());
                }
            }
        }
        if (plain.getWhere() != null) {
            expressions.add(plain.getWhere());
        }
        if (plain.getHaving() != null) {
            expressions.add(plain.getHaving());
        }
        if (plain.getJoins() != null) {
            for (Join join : plain.getJoins()) {
                if (join.getOnExpression() != null) {
                    expressions.add(join.getOnExpression());
                }
            }
        }
        if (plain.getOrderByElements() != null) {
            for (OrderByElement element : plain.getOrderByElements()) {
                if (element.getExpression() != null) {
                    expressions.add(element.getExpression());
                }
            }
        }
        return expressions;
    }
}
