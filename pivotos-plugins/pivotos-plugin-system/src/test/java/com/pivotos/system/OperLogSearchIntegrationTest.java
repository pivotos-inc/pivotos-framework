package com.pivotos.system;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.starter.search.simple.SimpleSearchProvider;
import com.pivotos.system.domain.dto.OperLogQuery;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.domain.vo.OperLogVO;
import com.pivotos.system.mapper.SysOperLogMapper;
import com.pivotos.system.search.OperLogIndexBootstrap;
import com.pivotos.system.search.OperLogSearchSupport;
import com.pivotos.system.service.OperLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作日志检索链路集成测试（S122）：验证「业务检索真的走 SearchTemplate」。
 *
 * <p>覆盖：写侧双写、六项条件映射、时间区间（simple 时间比较缺陷的回归）、排序分页、
 * 冷启动回灌、按主键删索引。
 * 回退路径（{@code enabled=false}）由 {@link OperLogSearchFallbackTest} 单独用另一套上下文覆盖。
 */
@SpringBootTest(classes = SystemTestApplication.class)
class OperLogSearchIntegrationTest {

    private static final String MODULE = "S122检索";
    private static final String OPERATOR = "s122-tester";

    @Autowired
    private OperLogService operLogService;

    @Autowired
    private OperLogSearchSupport searchSupport;

    @Autowired
    private OperLogIndexBootstrap bootstrap;

    @Autowired
    private SysOperLogMapper operLogMapper;

    @Autowired
    private SimpleSearchProvider provider;

    private final List<Long> createdIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        // 每个用例从干净索引开始（simple 是进程内 Map，上下文内共享）
        provider.clear();
    }

    @AfterEach
    void tearDown() {
        for (Long id : createdIds) {
            operLogMapper.deleteById(id);
        }
        createdIds.clear();
        provider.clear();
    }

    @Test
    void shouldIndexOnSaveAndBeSearchable() {
        SysOperLog log = newLog("删除", 0, LocalDateTime.of(2026, 9, 1, 10, 0));

        assertTrue(searchSupport.enabled(), "搜索 Starter 未装配，链路未生效");
        assertEquals(1, searchSupport.countIndexed());

        OperLogQuery query = new OperLogQuery();
        query.setModule(MODULE);
        PageResult<OperLogVO> page = operLogService.pageLogs(query);
        assertEquals(1, page.getTotal());
        assertEquals("删除", page.getList().get(0).getOperType());
        assertEquals(OPERATOR, page.getList().get(0).getOperName());
        assertNotNull(log.getId());
    }

    @Test
    void shouldMapAllQueryConditionsOntoSearch() {
        newLog("新增", 0, LocalDateTime.of(2026, 9, 2, 10, 0));
        newLog("修改", 1, LocalDateTime.of(2026, 9, 3, 10, 0));
        newLog("导出", 0, LocalDateTime.of(2026, 9, 4, 10, 0));

        // module 模糊
        assertEquals(3, query(q -> q.setModule(MODULE)).getTotal());
        // module 模糊 + operType 精确
        assertEquals(1, query(q -> {
            q.setModule(MODULE);
            q.setOperType("修改");
        }).getTotal());
        // operName 模糊
        assertEquals(3, query(q -> q.setOperName("s122")).getTotal());
        // status 精确：两条成功
        assertEquals(2, query(q -> {
            q.setModule(MODULE);
            q.setStatus(0);
        }).getTotal());
        // 组合不命中：不存在的操作人
        assertEquals(0, query(q -> q.setOperName("不存在的人")).getTotal());
    }

    @Test
    void shouldSupportTimeRangeQuery() {
        newLog("新增", 0, LocalDateTime.of(2026, 9, 1, 10, 0));
        newLog("新增", 0, LocalDateTime.of(2026, 9, 10, 10, 0));
        newLog("新增", 0, LocalDateTime.of(2026, 9, 20, 10, 0));

        // S122 回归：文档侧时间是字符串、条件侧是 LocalDateTime，simple 下必须真能检出
        assertEquals(2, query(q -> {
            q.setModule(MODULE);
            q.setBeginTime(LocalDateTime.of(2026, 9, 1, 0, 0));
            q.setEndTime(LocalDateTime.of(2026, 9, 15, 0, 0));
        }).getTotal());
        // 区间外的数据必须被排除（09-20 那条）
        assertEquals(1, query(q -> {
            q.setModule(MODULE);
            q.setBeginTime(LocalDateTime.of(2026, 9, 15, 0, 0));
        }).getTotal());
        // 单边区间
        assertEquals(1, query(q -> {
            q.setModule(MODULE);
            q.setBeginTime(LocalDateTime.of(2026, 9, 11, 0, 0));
        }).getTotal());
        assertEquals(2, query(q -> {
            q.setModule(MODULE);
            q.setEndTime(LocalDateTime.of(2026, 9, 10, 23, 59, 59));
        }).getTotal());
    }

    @Test
    void shouldSortByOperTimeDescAndPaginate() {
        newLog("新增", 0, LocalDateTime.of(2026, 9, 1, 10, 0));
        newLog("修改", 0, LocalDateTime.of(2026, 9, 2, 10, 0));
        newLog("删除", 0, LocalDateTime.of(2026, 9, 3, 10, 0));

        OperLogQuery query = new OperLogQuery();
        query.setModule(MODULE);
        query.setPageNum(1);
        query.setPageSize(2);
        PageResult<OperLogVO> first = operLogService.pageLogs(query);
        assertEquals(3, first.getTotal());
        assertEquals(2, first.getList().size());
        assertEquals(LocalDateTime.of(2026, 9, 3, 10, 0), first.getList().get(0).getOperTime(),
                "必须按操作时间倒序");
        assertEquals(LocalDateTime.of(2026, 9, 2, 10, 0), first.getList().get(1).getOperTime());

        query.setPageNum(2);
        PageResult<OperLogVO> second = operLogService.pageLogs(query);
        assertEquals(3, second.getTotal());
        assertEquals(1, second.getList().size());
        assertEquals(LocalDateTime.of(2026, 9, 1, 10, 0), second.getList().get(0).getOperTime());
    }

    @Test
    void shouldRebuildIndexFromDatabaseOnBootstrap() {
        newLog("新增", 0, LocalDateTime.of(2026, 9, 5, 10, 0));
        provider.clear();
        assertEquals(0, searchSupport.countIndexed(), "清空后索引应为空");

        bootstrap.bootstrap();
        assertTrue(searchSupport.countIndexed() > 0, "冷启动回灌应把库里的数据补回索引");

        OperLogQuery query = new OperLogQuery();
        query.setModule(MODULE);
        assertEquals(1, operLogService.pageLogs(query).getTotal());

        // 幂等：再来一次不应重复堆积（同 id 覆盖写）
        long after = searchSupport.countIndexed();
        bootstrap.bootstrap();
        assertEquals(after, searchSupport.countIndexed());
    }

    @Test
    void shouldRemoveIndexWhenLogDeleted() {
        SysOperLog log = newLog("删除", 0, LocalDateTime.of(2026, 9, 6, 10, 0));
        Long id = log.getId();
        assertEquals(1, searchSupport.countIndexed());

        searchSupport.deleteByIds(List.of(id));
        OperLogQuery query = new OperLogQuery();
        query.setModule(MODULE);
        assertEquals(0, operLogService.pageLogs(query).getTotal());
    }

    @Test
    void shouldKeepWorkingWhenQueryHasNoCondition() {
        newLog("新增", 0, LocalDateTime.of(2026, 9, 7, 10, 0));
        assertTrue(operLogService.pageLogs(new OperLogQuery()).getTotal() >= 1);
    }

    // ==================== 辅助 ====================

    private PageResult<OperLogVO> query(java.util.function.Consumer<OperLogQuery> customizer) {
        OperLogQuery query = new OperLogQuery();
        customizer.accept(query);
        return operLogService.pageLogs(query);
    }

    private SysOperLog newLog(String operType, Integer status, LocalDateTime operTime) {
        SysOperLog entity = new SysOperLog();
        entity.setModule(MODULE);
        entity.setOperType(operType);
        entity.setOperName(OPERATOR);
        entity.setOperUserId(1L);
        entity.setMethod("com.pivotos.S122.search()");
        entity.setRequestMethod("GET");
        entity.setRequestUrl("/api/system/log/oper/page");
        entity.setRequestParams("{}");
        entity.setStatus(status);
        entity.setDuration(10L);
        entity.setOperTime(operTime);
        operLogService.saveLog(entity);
        assertNotNull(entity.getId());
        createdIds.add(entity.getId());
        return entity;
    }
}
