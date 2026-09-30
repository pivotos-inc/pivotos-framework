package com.pivotos.starter.search.simple;

import com.pivotos.starter.search.api.enums.SearchLogic;
import com.pivotos.starter.search.api.query.SearchCriteria;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * simple 实现的条件求值器（确定性、无第三方依赖）。
 * <p>连接语义：{@link SearchLogic} 描述「本条件与前一个条件的连接关系」，
 * 首条件无前驱故按 AND 处理；与 MyBatis-Plus / Easy-ES wrapper 口径一致。
 * <p><b>S122 修订</b>：文档侧时间在「实体 → JSON → Map」后落成<b>字符串</b>（fastjson2 实测
 * {@code LocalDateTime → "2026-09-30 10:30:15"}），而条件侧是 {@code LocalDateTime} 对象。
 * 本类原先只在「双方都是时间对象」时才走毫秒比较，导致 <b>simple 实现下时间类条件恒不成立</b>
 * （compare 返回 null → GT/GE/LT/LE/BETWEEN 全部判 false，表现为「带时间区间的查询永远返回空」）。
 * 现按 {@link #toMillis(Object)} 做「时间字符串 ↔ 时间对象」双向归一。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class SimpleCriteriaMatcher {

    /**
     * 宽松本地时间格式：兼容 {@code yyyy-MM-dd HH:mm:ss} / {@code yyyy-MM-ddTHH:mm:ss}（可带毫秒）。
     * <p>前者是 fastjson2 序列化 LocalDateTime 的默认输出，后者是 ISO 标准输出；
     * 两者都必须支持，否则「文档里的时间」与「条件里的时间」无法对齐。
     */
    private static final DateTimeFormatter FLEX_LOCAL_DATE_TIME = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart().appendLiteral('T').optionalEnd()
            .optionalStart().appendLiteral(' ').optionalEnd()
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .toFormatter();

    private SimpleCriteriaMatcher() {
    }

    /**
     * 条件树求值
     */
    public static boolean matches(Map<String, Object> source, List<SearchCriteria> criteria) {
        if (criteria == null || criteria.isEmpty()) {
            return true;
        }
        boolean result = true;
        boolean hasPrev = false;
        for (SearchCriteria c : criteria) {
            boolean current = matchesOne(source, c);
            if (!hasPrev) {
                result = current;
                hasPrev = true;
            } else if (c.getLogic() == SearchLogic.OR) {
                result = result || current;
            } else {
                result = result && current;
            }
        }
        return result;
    }

    private static boolean matchesOne(Map<String, Object> source, SearchCriteria c) {
        Object value = source == null ? null : source.get(c.getField());
        return switch (c.getOp()) {
            case IS_NULL -> value == null;
            case IS_NOT_NULL -> value != null;
            case EQ -> eq(value, c.value());
            case NE -> value != null && !eq(value, c.value());
            case GT -> cmpGt(value, c.value());
            case GE -> cmpGe(value, c.value());
            case LT -> cmpLt(value, c.value());
            case LE -> cmpLe(value, c.value());
            case LIKE -> contains(text(value), text(c.value()));
            case LIKE_LEFT -> endsWith(text(value), text(c.value()));
            case LIKE_RIGHT -> startsWith(text(value), text(c.value()));
            case IN -> in(value, c.getValues());
            case NOT_IN -> value != null && !in(value, c.getValues());
            case BETWEEN -> between(value, c.getValues());
            // simple 无分词能力：MATCH 退化为「任意字符串字段包含」，语义弱化但行为确定
            case MATCH -> matchAny(source, c.value());
        };
    }

    // ==================== 比较基元 ====================

    private static boolean eq(Object a, Object b) {
        if (a == null || b == null) {
            return a == null && b == null;
        }
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        if (a.getClass() == b.getClass()) {
            return a.equals(b);
        }
        // 跨类型（如 Long id vs String id）按字符串归一比较，避免「100L 与 '100' 判不等」的意外
        return a.toString().equals(b.toString());
    }

    private static boolean cmpGt(Object a, Object b) {
        Integer r = compare(a, b);
        return r != null && r > 0;
    }

    private static boolean cmpGe(Object a, Object b) {
        Integer r = compare(a, b);
        return r != null && r >= 0;
    }

    private static boolean cmpLt(Object a, Object b) {
        Integer r = compare(a, b);
        return r != null && r < 0;
    }

    private static boolean cmpLe(Object a, Object b) {
        Integer r = compare(a, b);
        return r != null && r <= 0;
    }

    /**
     * 跨类型有序比较：Number→double，Boolean→int，时间→epoch 毫秒，其余→字符串。
     * <p>时间既接受 {@code Date/Instant/LocalDateTime/LocalDate}，也接受<b>时间字符串</b>
     * （S122：文档侧的时间就是字符串，见类注释）。
     *
     * @return null 表示「不可比」（参与比较的一方为空，或一侧是真时间而另一侧不可解析），此时条件不成立
     */
    public static Integer compare(Object a, Object b) {
        if (a == null || b == null) {
            return null;
        }
        if (a instanceof Number && b instanceof Number) {
            return Double.compare(((Number) a).doubleValue(), ((Number) b).doubleValue());
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return Boolean.compare((Boolean) a, (Boolean) b);
        }
        Long la = toMillis(a);
        Long lb = toMillis(b);
        if (la != null && lb != null) {
            return Long.compare(la, lb);
        }
        // 一侧是真时间对象、另一侧既不是时间也解析不出时间 → 不可比（不退化成字符串比较，避免出现意外命中）
        if (isTemporal(a) || isTemporal(b)) {
            return null;
        }
        return a.toString().compareTo(b.toString());
    }

    private static boolean isTemporal(Object v) {
        return v instanceof Date || v instanceof TemporalAccessor;
    }

    /**
     * 归一到 epoch 毫秒：Date / Instant / LocalDateTime / LocalDate / 时间字符串。
     *
     * @return null 表示「不是时间或无法解析」
     */
    private static Long toMillis(Object v) {
        if (v instanceof Date d) {
            return d.getTime();
        }
        if (v instanceof Instant i) {
            return i.toEpochMilli();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (v instanceof LocalDate ld) {
            return ld.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        }
        if (v instanceof String s) {
            return toMillis(s);
        }
        return null;
    }

    /**
     * 时间字符串 → epoch 毫秒：依次尝试 ISO instant（带 Z）、带偏移的 OffsetDateTime、
     * 宽松本地时间（空格或 T 分隔）、纯日期。全部失败返回 null（非时间文本不影响其它比较语义）。
     */
    private static Long toMillis(String text) {
        String v = text.trim();
        if (v.isEmpty()) {
            return null;
        }
        try {
            return Instant.parse(v).toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 继续尝试下一种形态
        }
        try {
            return OffsetDateTime.parse(v).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 继续尝试下一种形态
        }
        try {
            return LocalDateTime.parse(v, FLEX_LOCAL_DATE_TIME)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            // 继续尝试下一种形态
        }
        try {
            return LocalDate.parse(v).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static boolean in(Object value, List<Object> candidates) {
        if (value == null || candidates == null) {
            return false;
        }
        for (Object c : candidates) {
            if (eq(value, c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean between(Object value, List<Object> bound) {
        if (value == null || bound == null || bound.size() != 2) {
            return false;
        }
        Integer low = compare(value, bound.get(0));
        Integer high = compare(value, bound.get(1));
        return low != null && high != null && low >= 0 && high <= 0;
    }

    /**
     * simple 的 MATCH 语义：任一字符串字段包含关键词（大小写不敏感）
     */
    private static boolean matchAny(Map<String, Object> source, Object keyword) {
        String kw = text(keyword);
        if (kw == null || source == null) {
            return false;
        }
        for (Object v : source.values()) {
            if (contains(text(v), kw)) {
                return true;
            }
        }
        return false;
    }

    private static String text(Object v) {
        return v == null ? null : v.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean contains(String source, String target) {
        return source != null && target != null && source.contains(target);
    }

    private static boolean startsWith(String source, String prefix) {
        return source != null && prefix != null && source.startsWith(prefix);
    }

    private static boolean endsWith(String source, String suffix) {
        return source != null && suffix != null && source.endsWith(suffix);
    }
}
