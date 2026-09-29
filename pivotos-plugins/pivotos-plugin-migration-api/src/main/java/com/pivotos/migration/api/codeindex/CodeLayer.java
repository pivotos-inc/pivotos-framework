package com.pivotos.migration.api.codeindex;

/**
 * 代码分层约定枚举（A4-1 分层感知定位的分层标签）。
 *
 * <p>来源：S107 spike 实证（K2）——LLM 粗定位对 Controller / 接口 / DTO / 实体等「上层文件」
 * 的置信度系统性高于 Impl 实现层，fw 侧 top1 命中 0/5。实现期对策是「分层约定感知」：
 * 业务逻辑类改动默认落点为实现层（ServiceImpl / 页面），故仲裁阶段对上层文件施加确定性折扣、
 * 对实现层施加加成。权重口径集中在本枚举，避免散落各处。
 *
 * <p>weight 为仲裁打分用的分层因子（1.0 = 中性）：
 * <ul>
 *   <li>&gt; 1.0：实现层加成（业务改动默认落点）</li>
 *   <li>&lt; 1.0：上层/数据层折扣（spike 证实的过召回方向）</li>
 * </ul>
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
public enum CodeLayer {

    /** Controller / RestController：入口层（spike 证实最高频误召回层） */
    CONTROLLER(false, 0.70),

    /** Service 接口：契约层 */
    SERVICE_API(false, 0.75),

    /** Service 实现：业务逻辑默认落点 */
    SERVICE_IMPL(true, 1.25),

    /** Mapper / Repository：数据访问层 */
    MAPPER(false, 0.85),

    /** 实体 / DO */
    ENTITY(false, 0.55),

    /** 入参 DTO / BO / Query */
    DTO(false, 0.60),

    /** 出参 VO */
    VO(false, 0.60),

    /** 配置类 */
    CONFIG(false, 0.70),

    /** 工具类 */
    UTIL(false, 0.75),

    /** 切面 / 拦截器 / 监听器 */
    ASPECT(false, 0.70),

    /** Vue 页面/组件：前端交互与校验的落点 */
    VUE_PAGE(true, 1.10),

    /** 前端 api 层（api/**\/*.ts）：接口封装落点 */
    TS_API(true, 1.00),

    /** 前端其他 ts（composable/store/router 等） */
    TS_OTHER(false, 0.85),

    /** 未识别 */
    UNKNOWN(false, 0.85);

    private final boolean implementationLayer;

    private final double weight;

    CodeLayer(boolean implementationLayer, double weight) {
        this.implementationLayer = implementationLayer;
        this.weight = weight;
    }

    /** 是否为「实现层」（业务逻辑默认落点），仲裁时享有加成 */
    public boolean implementationLayer() {
        return implementationLayer;
    }

    /** 仲裁打分分层因子（1.0 中性） */
    public double weight() {
        return weight;
    }
}
