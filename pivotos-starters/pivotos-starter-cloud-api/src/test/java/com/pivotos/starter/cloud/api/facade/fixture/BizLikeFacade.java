package com.pivotos.starter.cloud.api.facade.fixture;

import com.pivotos.common.core.page.PageResult;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 与业务契约<b>同构</b>（不相等）的测试契约：每个方法刻意复刻一个业务 Facade 的
 * 「最难 marshalling 形态」。
 *
 * <p><b>为什么是同构而不是直接用 {@code IUserFacade}</b>：{@code starter-cloud-api}
 * 是三通道契约包，<b>刻意不依赖任何 plugin 的 {@code *-api} 模块</b>
 * （引入 starter 就拖进全部业务契约会让插件边界规则失效）。
 * 真实业务语义由 admin-server 侧的 {@code facade_http_business_test.py} 端到端兜住，
 * 这里只负责把 marshalling 难点在<b>默认回归集</b>里钉死 ——
 * 这类缺陷（泛型擦除 / 缺时间模块 / 异常映射）只有真跑一次 HTTP 往返才暴露得出来。
 *
 * <p>各方法对应的真实契约与难点：
 * <ul>
 *   <li>{@code listByIds / argElementType} ← {@code IUserFacade#listByIds(Collection<Long>)}，D1 泛型擦除</li>
 *   <li>{@code stats} ← {@code IStatsFacade#systemStats()}，{@code LocalDateTime} 需要 jsr310 模块</li>
 *   <li>{@code readFile} ← {@code ICodeIndexFacade#readFile}，{@code Optional} 需要 jdk8 模块</li>
 *   <li>{@code pageByUser} ← {@code IMessageFacade#pageByUser}，{@code PageResult<T>} + 查询对象</li>
 *   <li>{@code preview} ← {@code IGeneratorFacade#previewPluginSkeleton}，{@code Map<String,Object>}（且刻意不进白名单）</li>
 *   <li>{@code rerank} ← {@code IRerankFacade#rerank}，{@code List<String>} + record 返回值</li>
 *   <li>{@code touch} ← {@code IWorkflowFacade#urgeInstance} 同款 void 返回</li>
 *   <li>{@code reject / boom} ← 业务异常与非业务异常的两种映射分支</li>
 *   <li>{@code currentUserId / currentTenantId} ← 依赖 {@code LoginContext} 的业务方法（D3）</li>
 * </ul>
 */
public interface BizLikeFacade {

    /** 批量查询：泛型实参是 {@code Long}，元素类型必须是 Long 而不是 Integer */
    List<BizLikeDTO> listByIds(Collection<Long> ids);

    /** 把入参元素的<b>运行时类型</b>回显出来：D1 的最直接红绿信号 */
    String argElementType(Collection<Long> ids);

    /** 无参 + {@code LocalDateTime} 字段 */
    BizLikeDTO stats();

    /** {@code Optional} 返回值；path 为 {@code "missing"} 时返回空 */
    Optional<String> readFile(String repo, String path);

    /** 分页：{@code PageResult<T>} + 查询对象 */
    PageResult<BizLikeDTO> pageByUser(Long userId, BizLikePageQuery query);

    /** {@code Map<String,Object>} 入参与返回值（写方法替身：刻意不进白名单） */
    Map<String, Object> preview(Map<String, Object> params);

    /** {@code List<String>} 入参 + record 返回值 */
    List<BizLikeScore> rerank(String query, List<String> documents, int topN);

    /** void 返回 */
    void touch();

    /** 抛 {@code ServiceException}（业务拒绝） */
    void reject(String reason);

    /** 抛非业务异常（实现故障） */
    void boom();

    /**
     * 单标量参数（对应 {@code IFileFacade#buildFileUrl}）。
     *
     * <p>存在的唯一理由：{@code ["abc"]} 这种<b>单元素参数数组</b>是最容易被容器转换器
     * 静默摊平成标量的形态（摊平后参数个数变 0，方法定位直接 NO_METHOD）。
     * 必须有一个真实的单标量参数方法才能把这条红绿边界钉住。
     */
    String buildUrl(String key);

    /** 读 {@code LoginContext}：用于证明「业务方法看到的是入站恢复后的身份」 */
    Long currentUserId();

    /** 读 {@code TenantContext}：同上，租户维度 */
    Long currentTenantId();
}
