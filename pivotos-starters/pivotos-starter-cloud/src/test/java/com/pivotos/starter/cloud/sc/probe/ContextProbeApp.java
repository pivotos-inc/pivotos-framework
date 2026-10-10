package com.pivotos.starter.cloud.sc.probe;

import com.alibaba.fastjson2.JSONObject;
import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.cloud.api.context.CloudHeaders;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.core.context.TraceContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 端到端验证用的「下游」应用：一个真实 Tomcat + 真实过滤器链 + 一个回显端点。
 *
 * <p><b>为什么必须是真实容器</b>：要证明的是「请求线程里 ScopedValue 真的被绑上了」，
 * MockMvc 只能证明过滤器对象被调用，证明不了绑定生效；而同线程直接调
 * {@code CloudContextCodec.runWith} 更是自证（上游绑的值下游当然看得见）。
 * 只有「上游线程绑 → 真实 HTTP 出站 → 下游另一个线程读」才是真证据。
 *
 * <p><b>为什么刻意用 {@code @SpringBootApplication}（即全量自动配置）</b>：
 * 这里要的就是生产形态的过滤器链（TraceIdFilter → CloudContextFilter …），
 * 手动 {@code @ImportAutoConfiguration} 列清单很容易漏一条就得出假的绿。
 * 本项目其它测试禁用 {@code @EnableAutoConfiguration} 是因为它们跑在
 * {@code webEnvironment=NONE} 且 classpath 没有完整 servlet 栈，本应用两者都齐全。
 *
 * <p>回显内容刻意<b>同时给三样东西</b>，缺一就无法分辨「没恢复」与「没传过来」：
 * <ol>
 *   <li>上下文（恢复后的终态）—— 判据主体；</li>
 *   <li>入站原始请求头 —— 证明出站确实发出了，排除「网络/编解码问题」；</li>
 *   <li>执行线程名 —— 证明下游在另一个线程，排除「ScopedValue 泄漏导致的假阳性」。</li>
 * </ol>
 */
@SpringBootApplication
@RestController
public class ContextProbeApp {

    /** 与 {@code ProbedFacade} 契约一致：GET /__probe/echo */
    @GetMapping(value = "/__probe/echo", produces = MediaType.TEXT_PLAIN_VALUE)
    public String echo(HttpServletRequest request) {
        JSONObject body = new JSONObject();

        LoginUser user = LoginContext.get();
        body.put("login", LoginContext.isLogin());
        body.put("tenantId", TenantContext.get());
        body.put("userId", user == null ? null : user.getUserId());
        body.put("username", user == null ? null : user.getUsername());
        body.put("accountType", user == null ? null : user.getAccountType());
        body.put("traceId", TraceContext.get());
        body.put("thread", Thread.currentThread().getName());

        JSONObject headers = new JSONObject();
        for (String name : List.of(CloudHeaders.TENANT_ID, CloudHeaders.USER_ID, CloudHeaders.USERNAME,
            CloudHeaders.ACCOUNT_TYPE, CloudHeaders.TRACE_ID, CloudHeaders.INTERNAL_TOKEN)) {
            String value = request.getHeader(name);
            if (value != null) {
                headers.put(name, value);
            }
        }
        body.put("headers", headers);
        return body.toJSONString();
    }
}
