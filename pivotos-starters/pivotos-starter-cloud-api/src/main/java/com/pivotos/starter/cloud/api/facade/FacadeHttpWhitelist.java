package com.pivotos.starter.cloud.api.facade;

import org.springframework.context.ApplicationContext;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Facade HTTP 白名单：解析「接口级」与「方法级」两种粒度，并提供启动期自检。
 *
 * <p><b>语法</b>：
 * <pre>
 *   com.example.IUserFacade                        整个接口可调用
 *   com.example.IGeneratorFacade:previewCode;stats 只有列出的方法可调用（分号分隔）
 * </pre>
 * 空方法集合 = 该接口全部方法可调用（向后兼容 V3-S3 的纯接口名形态）。
 *
 * <p><b>为什么方法之间用分号而不是逗号（踩坑）</b>：{@code interfaces} 是
 * {@code List<String>}，Spring 的 relaxed binding 会把<b>单个字符串按逗号切成多个条目</b>。
 * 用逗号分隔方法名时，{@code A:m1,m2} 会被切成 {@code ["A:m1", "m2"]} ——
 * 结果是只有 m1 进了白名单，m2 变成一个永远匹配不上的孤儿条目，
 * 且<b>不报错</b>（实测：全部方法 404，日志里白正常）。分号不是列表分隔符，安全。
 *
 * <p><b>为什么必须支持方法级粒度</b>：14 个业务契约里读写方法是混在同一个接口上的
 * （{@code IMessageFacade#send} 会真发短信、{@code IWorkflowFacade#approveTask} 是审批通过、
 * {@code IGeneratorFacade#generateToProject} 直接往工程目录写文件）。
 * 只给接口级粒度的话，「想开放只读的 pageByUser」就必须把 send 一起开放，
 * 而这个端点的唯一闸门是内部凭证 —— 闸门一旦破了就是无差别副作用。
 *
 * <p><b>为什么自检只 WARN 不 FAIL</b>：微服务形态下 A 进程本来就只实现一部分 Facade，
 * 「白名单里配了本进程没有的接口」是合法部署形态（调用方打到另一个实例）。
 * 但「类名写错」与「本进程没实现」在日志里必须能被区分出来，否则排障只能靠猜。
 */
public final class FacadeHttpWhitelist {

    /** 接口全限定名 → 允许的方法名集合（空集合 = 全开） */
    private final Map<String, Set<String>> entries;

    private FacadeHttpWhitelist(Map<String, Set<String>> entries) {
        this.entries = entries;
    }

    /** 空白名单：任何接口都不允许（等价于端点不可用） */
    public static FacadeHttpWhitelist empty() {
        return new FacadeHttpWhitelist(Map.of());
    }

    public static FacadeHttpWhitelist parse(Collection<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return empty();
        }
        Map<String, Set<String>> parsed = new LinkedHashMap<>();
        for (String item : raw) {
            if (item == null || item.isBlank()) {
                continue;
            }
            String entry = item.trim();
            int colon = entry.indexOf(':');
            if (colon < 0) {
                parsed.put(entry, Set.of());
                continue;
            }
            String iface = entry.substring(0, colon).trim();
            if (iface.isEmpty()) {
                continue;
            }
            Set<String> methods = new LinkedHashSet<>();
            for (String method : entry.substring(colon + 1).split(";")) {
                String trimmed = method.trim();
                if (!trimmed.isEmpty()) {
                    methods.add(trimmed);
                }
            }
            // 已存在同名条目时合并方法集合（yml 里同一个接口写两行是常见写法，不该后者覆盖前者）
            parsed.computeIfAbsent(iface, k -> new LinkedHashSet<>()).addAll(methods);
        }
        return new FacadeHttpWhitelist(parsed);
    }

    /** 该接口是否在白名单里（不看方法） */
    public boolean covers(String iface) {
        return entries.containsKey(iface);
    }

    /**
     * 该接口的该方法是否允许调用。
     *
     * <p><b>方法不存在与接口不在白名单返回同一个 {@code false}</b>：与
     * {@link FacadeHttpConstants#ERROR_NOT_FOUND} 的口径一致 ——
     * 不让调用方通过响应差异探测「这个接口到底在不在白名单里」。
     */
    public boolean allows(String iface, String method) {
        Set<String> methods = entries.get(iface);
        if (methods == null) {
            return false;
        }
        return methods.isEmpty() || (method != null && methods.contains(method));
    }

    public Set<String> interfaces() {
        return entries.keySet();
    }

    public int size() {
        return entries.size();
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * 启动期自检：把「配置写错了」在日志里说清楚，而不是等调用时给一个无从下手的 404。
     *
     * @return 人类可读的告警列表（空列表 = 无问题）
     */
    public List<String> inspect(ApplicationContext context) {
        List<String> warnings = new ArrayList<>();
        if (entries.isEmpty()) {
            return warnings;
        }
        ClassLoader classLoader = context == null ? null : context.getClassLoader();
        for (Map.Entry<String, Set<String>> entry : entries.entrySet()) {
            String ifaceName = entry.getKey();
            Class<?> iface;
            try {
                iface = ClassUtils.forName(ifaceName, classLoader);
            } catch (Exception e) {
                warnings.add(ifaceName + "：classpath 上找不到该接口（类名写错？插件未引入？）");
                continue;
            }
            if (context != null && context.getBeanNamesForType(iface).length == 0) {
                warnings.add(ifaceName + "：本进程没有该 Facade 的实现 Bean"
                    + "（微服务形态下可能是合法部署，但打到本实例会 NOT_FOUND）");
            }
            Set<String> declared = entry.getValue();
            if (!declared.isEmpty()) {
                Set<String> known = new LinkedHashSet<>();
                for (Method method : iface.getMethods()) {
                    known.add(method.getName());
                }
                for (String method : declared) {
                    if (!known.contains(method)) {
                        warnings.add(ifaceName + "#" + method + "：白名单指定的方法在接口上不存在");
                    }
                }
            }
            for (String overload : duplicateArityMethods(iface)) {
                warnings.add(ifaceName + "#" + overload
                    + "：存在同名同元数重载，按「名+元数」定位只会命中其中一个");
            }
        }
        return warnings;
    }

    /**
     * 找出「同名 + 同参数个数」的方法：{@code FacadeInvocationSupport#resolveMethod}
     * 按元数收敛，这类重载必然有一个永远调不到。
     */
    private static List<String> duplicateArityMethods(Class<?> iface) {
        Map<String, Integer> seen = new TreeMap<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (Method method : iface.getMethods()) {
            String key = method.getName() + "/" + method.getParameterCount();
            if (seen.put(key, 1) != null) {
                duplicates.add(method.getName() + "(" + method.getParameterCount() + " 参)");
            }
        }
        return new ArrayList<>(duplicates);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof FacadeHttpWhitelist other && entries.equals(other.entries);
    }

    @Override
    public int hashCode() {
        return Objects.hash(entries);
    }
}
