package com.pivotos.starter.cloud.api.support;

import org.springframework.context.ApplicationContext;

/**
 * Facade 反射调用的公共工具：local 通道 Servlet 与 cloud 通道 HTTP 端点共用同一套定位语义。
 *
 * <p>抽到这里的原因与 {@link FacadeProxySupport} 同源：定位语义若各写一份，
 * 「必须先取 {@code $Local}、绝不能按类型取到远程代理」这条纪律必然在其中一份里漂移，
 * 而漂移的形态是<b>运行时自调用死循环</b>（代理 → HTTP → 本进程代理 → …），
 * 编译期单测全部绿，只有真起服务才炸。
 *
 * <p><b>落位 {@code cloud-api} 而不是各通道实现包</b>：三个通道都要能用同一份语义，
 * 且 local / cloud 实现包之间没有编译依赖（互不可见）。
 */
public final class FacadeInvocationSupport {

    /** 找不到本地实现时的错误码前缀（wire 协议的一部分，勿改） */
    public static final String NO_LOCAL_IMPL = "NO_LOCAL_IMPL";

    /** 找不到方法时的错误码前缀（wire 协议的一部分，勿改） */
    public static final String NO_METHOD = "NO_METHOD";

    private FacadeInvocationSupport() {
    }

    /**
     * 定位 Facade 的真实实现 Bean。
     *
     * <p><b>顺序是硬纪律</b>：优先 {@code <beanName>$Local}（被远程代理替换后本地实现的留存形态），
     * 只有在没有替换发生时才按类型取。<b>绝不先按类型取</b>——那时取到的可能是远程代理本身。
     *
     * @return 实现对象；{@code null} 表示本进程没有该 Facade 的实现
     */
    public static Object resolveTarget(ApplicationContext context, Class<?> iface) {
        if (context == null || iface == null) {
            return null;
        }
        String[] names = context.getBeanNamesForType(iface);
        if (names.length == 0) {
            return null;
        }
        for (String name : names) {
            if (name.endsWith(FacadeProxySupport.LOCAL_SUFFIX)) {
                return context.getBean(name);
            }
        }
        return context.getBean(names[0]);
    }

    /**
     * 按「方法名 + 元数」定位方法。
     *
     * <p>刻意<b>不按形参类型列表精确匹配</b>：客户端序列化实参后，形参声明类型无法跨进程逐字节还原
     * （尤其是基本类型装箱与接口/实现差异），按元数收敛是 RPC 侧的通行做法；
     * 代价是同名同元数重载会取第一个（与既有 {@code FacadeRpcServlet} 口径一致，不多也不少）。
     */
    public static java.lang.reflect.Method resolveMethod(Class<?> iface, String methodName, int argCount) {
        java.lang.reflect.Method fallback = null;
        for (java.lang.reflect.Method method : iface.getMethods()) {
            if (!method.getName().equals(methodName) || method.getParameterCount() != argCount) {
                continue;
            }
            if (fallback == null) {
                fallback = method;
            }
            if (argCount == 0) {
                return method;
            }
        }
        return fallback;
    }
}
