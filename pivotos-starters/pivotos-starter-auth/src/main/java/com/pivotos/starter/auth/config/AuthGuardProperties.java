package com.pivotos.starter.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 匿名访问闸门配置（v3.1.0 生产化 · A4）。
 *
 * <p><b>为什么需要它</b>：全项目鉴权是「注解驱动 + 业务代码取 {@code LoginContext} 兜底」的双轨制
 * （{@code AuthAutoConfiguration} 只注册 SaInterceptor，不做全局路由拦截）。这套机制对<b>自己写的
 * 控制器</b>有效，但对<b>第三方 jar 自带的端点</b>完全无效——那些类上没有、也不可能加上
 * {@code @SaCheckLogin}。实证（§10-K）：warm-flow 自带的 7 个 GET 端点匿名即可取到
 * {@code code=200} 的流程定义结构 / 处理器字典 / 监听器清单。
 *
 * <p><b>本闸门是路由层兜底</b>：按 Ant 前缀匹配，命中且未登录即写「未登录」响应体，
 * 不需要改第三方一行代码，也不影响第三方端点的正常业务语义。
 *
 * <p><b>默认只守 {@code /warm-flow/**}</b>：这是当前唯一实证漏出的第三方前缀。
 * 新增前缀请显式配置（白名单式扩张，避免误伤）。
 *
 * <p><b>响应口径与 Sa-Token 保持一致</b>：HTTP 200 + {@code code=1002}。
 * 既有 {@code @SaCheckLogin} 端点就是这个形态，前端与 E2E 已按此断言；
 * 若这里改成 401 会造成同一系统两种鉴权失败语义。
 * （真正的 HTTP 状态码语义只在 V3.1-B A2 的探活端点上做，鉴权面不动。）
 */
@ConfigurationProperties(prefix = "pivotos.auth.guard")
public class AuthGuardProperties {

    /** 总开关：false 即不注册过滤器（回滚只需一个环境变量） */
    private boolean enabled = true;

    /** 需要登录后才能访问的路径前缀（Ant 语法） */
    private List<String> paths = new ArrayList<>(List.of("/warm-flow/**"));

    /** 命中前缀但要放行的例外（Ant 语法），用于个别确需匿名的第三方端点 */
    private List<String> excludePaths = new ArrayList<>();

    /** 未登录提示语（可被运维改成更中性的文案） */
    private String message = "该端点要求登录后访问";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPaths() {
        return paths;
    }

    public void setPaths(List<String> paths) {
        this.paths = paths;
    }

    public List<String> getExcludePaths() {
        return excludePaths;
    }

    public void setExcludePaths(List<String> excludePaths) {
        this.excludePaths = excludePaths;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
