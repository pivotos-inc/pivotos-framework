package com.pivotos.starter.cloud.api.facade;

import com.pivotos.common.api.context.ContextFacade;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Facade HTTP 暴露开关（{@code pivotos.cloud.facade-http.*}）。
 *
 * <p><b>设计由来（V3-S3 拍板 ④ 方案 C）</b>：微服务形态要把 Facade 契约变成可跨进程调用的
 * HTTP 端点，最直觉的做法是在 {@code *-api} 契约接口上加 {@code @RequestMapping}——那会迫使
 * 全部契约模块引入 {@code spring-web}，与「契约层零框架依赖」纪律正面冲突；且 Spring MVC
 * 对「注解写在接口上」只认方法映射、<b>参数注解取的是实现类方法</b>（复杂 DTO 会退化成
 * 表单绑定）。因此改走运行时适配：契约层一字不改，由 cloud starter 按 Facade 元数据
 * 反射注册端点，消费方仍然是「注入接口、直接调用」。
 *
 * <p><b>默认全关 + 白名单</b>：端点能按名反射调任意方法，敞开就是后门。
 * 默认不注册；即便注册也强制内部凭证，且只有白名单里的接口可以被调用。
 */
@Data
public class FacadeHttpProperties {

    /**
     * 是否注册 Facade HTTP 端点（默认 false）。
     *
     * <p>默认值必须保持 false：这是「引入 Starter 但保持零行为」一票否决点的边界之一——
     * 单体形态下 classpath 里有这套代码，但一个端点都不许出现在端口上。
     */
    private boolean enabled = false;

    /**
     * 端点基路径（默认 {@code /__facade}）。
     *
     * <p>刻意保留 {@code __} 内务前缀：这条路径是基础设施而非业务 API，不能进 Swagger/菜单，
     * 也不该与业务 URL 混在同一个扁平命名空间里。
     */
    private String path = FacadeHttpConstants.BASE_PATH;

    /**
     * 允许被 HTTP 调用的 Facade 接口白名单，支持两种粒度（V3-S4）：
     * <pre>
     *   com.example.IUserFacade                      整个接口可调用
     *   com.example.IGeneratorFacade:previewCode;stats 只有列出的方法可调用（分号分隔）
     * </pre>
     * 条目之间用逗号（{@code List<String>} 的自然分隔），方法之间必须用<b>分号</b> ——
     * 逗号会被 Spring 当成条目分隔符（详见 {@link FacadeHttpWhitelist}）。
     *
     * <p><b>方法级粒度是必需的，不是可选糖</b>：14 个业务契约里读写方法混在同一接口上
     * （{@code IMessageFacade#send} 真发短信、{@code IWorkflowFacade#approveTask} 审批通过、
     * {@code IGeneratorFacade#generateToProject} 往工程目录写文件）。
     * 只有接口级粒度的话，「想开放只读方法」就必须把写方法一起开放。
     *
     * <p><b>为什么默认值仍然只有 {@link ContextFacade}</b>（V3-S4 拍板）：
     * 这个端点的唯一闸门是内部凭证，默认值一旦放开，只要有人打开 {@code enabled}，
     * 51 个方法（含全部写方法）就同时可用了。「默认形态零行为」这条边界
     * 不能靠「没人去开那个开关」来守 —— 业务接口必须由部署方显式引用
     * （见 {@link FacadeHttpPresets#READ_ONLY_BUSINESS} 预置只读组）。
     *
     * <p>试点范围（V3-S3）：默认只有 {@link ContextFacade} —— 它是只读、无副作用、
     * 返回值恰好等于「当前进程看到了什么上下文」，正好当作跨进程身份/租户传播的取证探针。
     */
    private List<String> interfaces = new ArrayList<>(List.of(ContextFacade.class.getName()));

    /** 单次调用参数个数硬上限（防恶意构造超大 body 触发反射找方法） */
    private int maxArgs = 16;
}
