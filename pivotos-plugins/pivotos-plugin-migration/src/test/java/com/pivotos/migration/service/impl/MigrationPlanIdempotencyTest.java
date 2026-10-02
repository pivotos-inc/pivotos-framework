package com.pivotos.migration.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pivotos.ai.api.facade.IAiFacade;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.migration.api.enums.MigrationErrorCode;
import com.pivotos.migration.config.MigrationProperties;
import com.pivotos.migration.domain.entity.MigrationArtifact;
import com.pivotos.migration.domain.entity.MigrationIrNode;
import com.pivotos.migration.domain.entity.MigrationStep;
import com.pivotos.migration.domain.entity.MigrationTask;
import com.pivotos.migration.domain.enums.MigrationTaskStatus;
import com.pivotos.migration.engine.parse.SourceCodeParserChain;
import com.pivotos.migration.mapper.MigrationArtifactMapper;
import com.pivotos.migration.mapper.MigrationFileMapper;
import com.pivotos.migration.mapper.MigrationIrNodeMapper;
import com.pivotos.migration.mapper.MigrationLogMapper;
import com.pivotos.migration.mapper.MigrationStepMapper;
import com.pivotos.migration.mapper.MigrationTaskMapper;
import com.pivotos.migration.service.MigrationFileService;
import com.pivotos.migration.service.MigrationProgressNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L10 清偿：迁移任务 plan 幂等 + reset 端点。
 *
 * <p>历史欠账原文：「迁移任务 plan 重复触发产生双批步骤（30 条 migration_step 并存，
 * 旧批含已完成残留），E2E 计数断言撞车；产品缺陷候选：plan 重新生成应先清旧步骤（幂等）、
 * rollback 端点应覆盖步骤清理」。
 *
 * <p>本轮契约钉死三件事：
 * <ol>
 *   <li>每次 plan 都先清旧步骤（且清在插入之前），重复触发不会双批并存；</li>
 *   <li>AI 未装载的降级分支同样先清——旧实现只在该分支置 PLANNED 却不清步骤；</li>
 *   <li>resetPlan 清步骤 + 清「未落盘」产物并回退到 ANALYZED，E2E 不再需要 pymysql 物理删行。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("L10 迁移 plan 幂等与复位")
class MigrationPlanIdempotencyTest {

    private static final Long TASK_ID = 1L;

    @Mock
    private MigrationProperties migrationProperties;
    @Mock
    private MigrationFileService migrationFileService;
    @Mock
    private MigrationFileMapper migrationFileMapper;
    @Mock
    private MigrationArtifactMapper migrationArtifactMapper;
    @Mock
    private MigrationIrNodeMapper migrationIrNodeMapper;
    @Mock
    private MigrationLogMapper migrationLogMapper;
    @Mock
    private MigrationStepMapper migrationStepMapper;
    @Mock
    private MigrationTaskMapper migrationTaskMapper;
    @Mock
    private SourceCodeParserChain parserChain;
    @Mock
    private ObjectProvider<IAiFacade> aiFacadeProvider;
    @Mock
    private MigrationProgressNotifier progressNotifier;
    @Mock
    private IAiFacade aiFacade;

    @InjectMocks
    private MigrationTaskServiceImpl service;

    /** selectById 最近一次返回的任务实例（用于断言状态被写回） */
    private MigrationTask lastTask;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "baseMapper", migrationTaskMapper);
        when(migrationIrNodeMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<MigrationIrNode>>any()))
                .thenReturn(List.of());
        when(migrationArtifactMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<MigrationArtifact>>any()))
                .thenReturn(List.of());
    }

    // ==================== plan 幂等 ====================

    @Test
    @DisplayName("plan 重复触发：每次都先清旧步骤，两次调用只留最后一批（不双批并存）")
    void planIsIdempotent() {
        task(MigrationTaskStatus.ANALYZED);
        when(aiFacadeProvider.getIfAvailable()).thenReturn(aiFacade);
        when(aiFacade.chatWithSystem(any(), any())).thenReturn(threeStepsJson());

        service.generateMigrationPlan(TASK_ID);
        service.generateMigrationPlan(TASK_ID);

        // 两次 plan → 两次清空；第二次的清空发生在第一次插入的 3 条之后
        verify(migrationStepMapper, times(2))
                .delete(ArgumentMatchers.<LambdaQueryWrapper<MigrationStep>>any());
        verify(migrationStepMapper, times(6)).insert(any(MigrationStep.class));

        var order = inOrder(migrationStepMapper);
        order.verify(migrationStepMapper).delete(ArgumentMatchers.<LambdaQueryWrapper<MigrationStep>>any());
        order.verify(migrationStepMapper, times(3)).insert(any(MigrationStep.class));
        order.verify(migrationStepMapper).delete(ArgumentMatchers.<LambdaQueryWrapper<MigrationStep>>any());
        order.verify(migrationStepMapper, times(3)).insert(any(MigrationStep.class));
    }

    @Test
    @DisplayName("plan：清步骤先于插步骤（顺序反了仍会双批）")
    void clearBeforeInsert() {
        task(MigrationTaskStatus.ANALYZED);
        when(aiFacadeProvider.getIfAvailable()).thenReturn(aiFacade);
        when(aiFacade.chatWithSystem(any(), any())).thenReturn(threeStepsJson());

        service.generateMigrationPlan(TASK_ID);

        var order = inOrder(migrationStepMapper);
        order.verify(migrationStepMapper).delete(ArgumentMatchers.<LambdaQueryWrapper<MigrationStep>>any());
        order.verify(migrationStepMapper, times(3)).insert(any(MigrationStep.class));
    }

    @Test
    @DisplayName("plan：AI 未装载的降级分支同样先清旧步骤（旧实现此处不清）")
    void clearEvenWhenAiAbsent() {
        task(MigrationTaskStatus.ANALYZED);
        when(aiFacadeProvider.getIfAvailable()).thenReturn(null);

        String summary = service.generateMigrationPlan(TASK_ID);

        assertEquals("{}", summary);
        verify(migrationStepMapper, times(1))
                .delete(ArgumentMatchers.<LambdaQueryWrapper<MigrationStep>>any());
        verify(migrationStepMapper, never()).insert(any(MigrationStep.class));
    }

    @Test
    @DisplayName("plan：清步骤同时清「未落盘」产物，不留孤儿 artifact")
    void clearPendingArtifactsOnPlan() {
        task(MigrationTaskStatus.ANALYZED);
        when(aiFacadeProvider.getIfAvailable()).thenReturn(aiFacade);
        when(aiFacade.chatWithSystem(any(), any())).thenReturn(threeStepsJson());

        // 一条已落盘（applied=true）+ 一条未落盘
        when(migrationArtifactMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<MigrationArtifact>>any()))
                .thenReturn(List.of(artifact(101L, true), artifact(102L, false)));

        service.generateMigrationPlan(TASK_ID);

        verify(migrationArtifactMapper).deleteBatchIds(List.of(102L));
    }

    // ==================== resetPlan ====================

    @Test
    @DisplayName("resetPlan：清步骤 + 清未落盘产物，任务回到 ANALYZED 且计划字段归零")
    void resetPlanClearsAndRollsBackStatus() {
        task(MigrationTaskStatus.PLANNED);
        when(migrationStepMapper.selectCount(ArgumentMatchers.<LambdaQueryWrapper<MigrationStep>>any())).thenReturn(30L);
        when(migrationArtifactMapper.selectCount(ArgumentMatchers.<LambdaQueryWrapper<MigrationArtifact>>any())).thenReturn(12L);
        when(migrationArtifactMapper.selectList(ArgumentMatchers.<LambdaQueryWrapper<MigrationArtifact>>any()))
                .thenReturn(List.of(artifact(201L, true), artifact(202L, false)));

        int cleared = service.resetPlan(TASK_ID);

        assertEquals(42, cleared);
        verify(migrationStepMapper).delete(ArgumentMatchers.<LambdaQueryWrapper<MigrationStep>>any());
        assertEquals(MigrationTaskStatus.ANALYZED.getCode(), lastTask.getStatus());
        assertNull(lastTask.getMigrationPlan());
        assertEquals(0, lastTask.getTotalSteps());

        // 已落盘的行必须留下（否则 rollback 找不到磁盘文件）
        verify(migrationArtifactMapper).deleteBatchIds(List.of(202L));
    }

    @Test
    @DisplayName("resetPlan：执行中 / 已完成状态禁止复位（防抹掉真实产物）")
    void resetPlanRejectedWhenExecuting() {
        task(MigrationTaskStatus.EXECUTING);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.resetPlan(TASK_ID));
        assertEquals(MigrationErrorCode.TASK_STATUS_INVALID.getCode(), ex.getCode());
        verify(migrationStepMapper, never()).delete(ArgumentMatchers.<LambdaQueryWrapper<MigrationStep>>any());
    }

    @Test
    @DisplayName("resetPlan：任务不存在抛 8000")
    void resetPlanTaskNotFound() {
        when(migrationTaskMapper.selectById(TASK_ID)).thenReturn(null);
        ServiceException ex = assertThrows(ServiceException.class, () -> service.resetPlan(TASK_ID));
        assertEquals(MigrationErrorCode.TASK_NOT_FOUND.getCode(), ex.getCode());
    }

    // ==================== helper ====================

    private MigrationTask task(MigrationTaskStatus status) {
        // 每次 selectById 都返回全新实例：真实链路里每次 plan 都是独立的一次读，
        // 复用同一实例会让「第一次 plan 把状态改成 PLANNED」直接把第二次挡在门外，
        // 掩盖「重复触发」这个被测场景本身。
        when(migrationTaskMapper.selectById(TASK_ID)).thenAnswer(inv -> {
            MigrationTask t = new MigrationTask();
            t.setId(TASK_ID);
            t.setName("s124-demo");
            t.setStatus(status.getCode());
            t.setMigrationPlan("old-plan");
            t.setTotalSteps(30);
            lastTask = t;
            return t;
        });
        return new MigrationTask();
    }

    private static MigrationArtifact artifact(Long id, boolean applied) {
        MigrationArtifact a = new MigrationArtifact();
        a.setId(id);
        a.setTaskId(TASK_ID);
        a.setApplied(applied);
        return a;
    }

    private static String threeStepsJson() {
        return """
                [
                  {"stepNo":1,"name":"后端骨架","stepType":"BACKEND","moduleId":"core","moduleName":"核心","description":"d1"},
                  {"stepNo":2,"name":"前端页面","stepType":"FRONTEND","moduleId":"web","moduleName":"前端","description":"d2"},
                  {"stepNo":3,"name":"数据库","stepType":"DB","moduleId":"db","moduleName":"数据库","description":"d3"}
                ]
                """;
    }
}
