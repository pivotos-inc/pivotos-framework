package com.pivotos.starter.search.esjava.support;

/**
 * ES 服务端版本（不可变值对象）。
 * <p>存在理由：一套 es-java 实现要同时服务 ES 7.17 / 8.x / 9.x，启动期必须先知道对面是谁——
 * 版本差异（dynamic_templates 的 match_mapping_type 形态、兼容头是否被接受）都由此决定，
 * 且<b>版本不支持时必须回落 simple 而不是让应用起不来</b>。
 *
 * <table>
 *   <caption>支持区间</caption>
 *   <tr><td>7.17.x</td><td>支持（需 compatibility-mode=true）</td></tr>
 *   <tr><td>8.x</td><td>支持</td></tr>
 *   <tr><td>9.x</td><td>支持（客户端 8.19.x 与 9.x wire 兼容：9.5.3 的 minimum_wire_compatibility_version=8.19.0）</td></tr>
 *   <tr><td>7.0~7.16 / 10.x+</td><td>不支持 → WARN + 回落 simple</td></tr>
 * </table>
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EsServerVersion {

    /** 支持区间下限的主版本 */
    public static final int MIN_MAJOR = 7;

    /** 主版本为 7 时的次版本下限（7.0~7.16 缺 compatibility header 支持，不在区间内） */
    public static final int MIN_MINOR_OF_MAJOR_7 = 17;

    /** 支持区间上限的主版本（超出即未知形态，宁可回落也不要赌） */
    public static final int MAX_MAJOR = 9;

    /** 探测失败（不可达 / 版本号解析不出）时的哨兵 */
    public static final EsServerVersion UNKNOWN = new EsServerVersion("unknown", 0, 0, 0);

    private final String raw;
    private final int major;
    private final int minor;
    private final int patch;

    private EsServerVersion(String raw, int major, int minor, int patch) {
        this.raw = raw;
        this.major = major;
        this.minor = minor;
        this.patch = patch;
    }

    /**
     * 解析 {@code GET /} 返回的 version.number（形如 {@code 9.5.3} / {@code 7.17.28} / {@code 8.19.0-SNAPSHOT}）。
     * 解析不出一律返回 {@link #UNKNOWN}——版本号拿不到时按「不支持」处理更安全（回落 simple）。
     */
    public static EsServerVersion parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return UNKNOWN;
        }
        String[] parts = raw.trim().split("[.\\-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
            int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
            return new EsServerVersion(raw.trim(), major, minor, patch);
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    public boolean isUnknown() {
        return major <= 0;
    }

    /** 是否落在支持区间（7.17 ~ 9.x） */
    public boolean isSupported() {
        if (isUnknown()) {
            return false;
        }
        if (major < MIN_MAJOR || major > MAX_MAJOR) {
            return false;
        }
        return major != MIN_MAJOR || minor >= MIN_MINOR_OF_MAJOR_7;
    }

    /** 兼容头（compatible-with=7）是否适用于该服务端 */
    public boolean needsCompatibilityHeader() {
        return !isUnknown() && major == 7;
    }

    public String raw() {
        return raw;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    public int patch() {
        return patch;
    }

    /** 支持区间的人类可读文本（日志用） */
    public static String supportRangeText() {
        return MIN_MAJOR + "." + MIN_MINOR_OF_MAJOR_7 + " ~ " + MAX_MAJOR + ".x";
    }

    @Override
    public String toString() {
        return raw;
    }
}
