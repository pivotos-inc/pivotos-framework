package com.pivotos.system;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.OperLogQuery;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.domain.vo.OperLogVO;
import com.pivotos.system.mapper.SysOperLogMapper;
import com.pivotos.system.search.OperLogSearchSupport;
import com.pivotos.system.service.OperLogService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 操作日志检索回退路径（S122）：{@code pivotos.search.oper-log.enabled=false} 时
 * 检索能力整体关闭，分页查询回到 MyBatis-Plus 路径——这是本 Sprint 的一键回滚开关，必须钉死。
 *
 * <p>用 {@code properties} 起独立上下文，避免污染 {@link OperLogSearchIntegrationTest} 的上下文。
 */
@SpringBootTest(classes = SystemTestApplication.class,
        properties = "pivotos.search.oper-log.enabled=false")
class OperLogSearchFallbackTest {

    private static final String MODULE = "S122回退";

    @Autowired
    private OperLogService operLogService;

    @Autowired
    private OperLogSearchSupport searchSupport;

    @Autowired
    private SysOperLogMapper operLogMapper;

    private final List<Long> createdIds = new ArrayList<>();

    @AfterEach
    void tearDown() {
        for (Long id : createdIds) {
            operLogMapper.deleteById(id);
        }
        createdIds.clear();
    }

    @Test
    void shouldDisableSearchCapability() {
        assertFalse(searchSupport.enabled());
        assertNull(searchSupport.search(new OperLogQuery()), "关闭后检索应直接返回 null 由调用方回退");
    }

    @Test
    void shouldStillPageFromDatabaseWhenSearchDisabled() {
        SysOperLog entity = new SysOperLog();
        entity.setModule(MODULE);
        entity.setOperType("新增");
        entity.setOperName("fallback-tester");
        entity.setOperUserId(1L);
        entity.setMethod("com.pivotos.S122.fallback()");
        entity.setRequestMethod("GET");
        entity.setRequestUrl("/api/system/log/oper/page");
        entity.setStatus(0);
        entity.setDuration(5L);
        entity.setOperTime(LocalDateTime.of(2026, 9, 8, 10, 0));
        operLogService.saveLog(entity);
        createdIds.add(entity.getId());

        OperLogQuery query = new OperLogQuery();
        query.setModule(MODULE);
        PageResult<OperLogVO> page = operLogService.pageLogs(query);
        assertEquals(1, page.getTotal());
        assertEquals(MODULE, page.getList().get(0).getModule());
        assertTrue(searchSupport.countIndexed() < 0, "关闭检索后不应回写索引（计数不可用）");
    }

    @Test
    void shouldNotWriteIndexWhenDisabled() {
        SysOperLog entity = new SysOperLog();
        entity.setModule(MODULE);
        entity.setOperType("新增");
        entity.setOperName("fallback-tester");
        entity.setStatus(0);
        entity.setOperTime(LocalDateTime.of(2026, 9, 9, 10, 0));
        operLogService.saveLog(entity);
        createdIds.add(entity.getId());

        assertEquals(-1L, searchSupport.countIndexed(), "关闭检索后索引计数不可用（即未写索引）");
        OperLogQuery query = new OperLogQuery();
        query.setModule(MODULE);
        assertNull(searchSupport.search(query));
    }
}
