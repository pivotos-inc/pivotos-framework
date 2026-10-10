package com.pivotos.starter.cloud.api.facade.fixture;

import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * {@link BizLikeFacade} 的本地实现。
 *
 * <p>{@link #listByIds} 里刻意写成 {@code for (Long id : ids)}：
 * 一旦参数被反序列化成 {@code ArrayList<Integer>}，这里立刻 ClassCastException，
 * 端点回 500 —— 这正是 D1（泛型擦除）在真实业务实现里的爆发形态。
 * 单靠「返回 200」是不能证明泛型没丢的，所以另配 {@link #argElementType}
 * 把元素的运行时类型直接回显出来当硬证据。
 */
public class BizLikeLocalFacade implements BizLikeFacade {

    /** 固定时间戳：让断言可以逐字符比对 ISO-8601 形态 */
    public static final LocalDateTime FIXED_TIME = LocalDateTime.of(2026, 10, 10, 12, 30, 45);

    private final AtomicInteger touchCount = new AtomicInteger();

    public int touchCount() {
        return touchCount.get();
    }

    @Override
    public List<BizLikeDTO> listByIds(Collection<Long> ids) {
        List<BizLikeDTO> result = new ArrayList<>();
        for (Long id : ids) {
            result.add(new BizLikeDTO(id, "user-" + id, FIXED_TIME));
        }
        return result;
    }

    @Override
    public String argElementType(Collection<Long> ids) {
        return ids.iterator().next().getClass().getName();
    }

    @Override
    public BizLikeDTO stats() {
        return new BizLikeDTO(1L, "stat", FIXED_TIME);
    }

    @Override
    public Optional<String> readFile(String repo, String path) {
        return "missing".equals(path) ? Optional.empty() : Optional.of("content-of-" + repo + "/" + path);
    }

    @Override
    public PageResult<BizLikeDTO> pageByUser(Long userId, BizLikePageQuery query) {
        // name 里回显查询对象的字段：证明嵌套查询对象真的被反序列化了（不是拿到一个空壳）
        String name = "msg-for-" + userId + "-rs" + (query == null ? "null" : query.getReadStatus());
        return new PageResult<>(List.of(new BizLikeDTO(userId, name, FIXED_TIME)), 1L, 1, 10);
    }

    @Override
    public Map<String, Object> preview(Map<String, Object> params) {
        return Map.of("echo", params);
    }

    @Override
    public List<BizLikeScore> rerank(String query, List<String> documents, int topN) {
        return List.of(new BizLikeScore(0, 0.9d));
    }

    @Override
    public void touch() {
        touchCount.incrementAndGet();
    }

    @Override
    public void reject(String reason) {
        throw new ServiceException(1500, reason);
    }

    @Override
    public void boom() {
        throw new IllegalStateException("secret-internal-detail-must-not-leak");
    }

    @Override
    public String buildUrl(String key) {
        return "url:" + key;
    }

    @Override
    public Long currentUserId() {
        return LoginContext.getUserId();
    }

    @Override
    public Long currentTenantId() {
        return TenantContext.get();
    }
}
