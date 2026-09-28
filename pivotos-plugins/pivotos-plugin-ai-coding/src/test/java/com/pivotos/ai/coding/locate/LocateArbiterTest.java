package com.pivotos.ai.coding.locate;

import com.pivotos.migration.api.codeindex.CodeLayer;
import com.pivotos.migration.api.codeindex.CodeSymbol;
import com.pivotos.migration.api.codeindex.FileSymbolTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 确定性仲裁器测试（S110 分层约定感知的定量验证）。
 *
 * <p>核心断言：同置信度下实现层必须赢过上层（spike K2 的反向修正），符号命中能把真正的
 * 落点从同层候选里挑出来——两条都是一票否决点（答案文件命中率 100% / 定位准确率 ≥7/8）的
 * 直接支撑，必须锁死。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@DisplayName("LocateArbiter 测试")
class LocateArbiterTest {

    @Test
    @DisplayName("分层因子：实现层归一为 1，最低权重层归一为 0")
    void layerFactorRange() {
        assertEquals(1.0, LocateArbiter.layerFactor(CodeLayer.SERVICE_IMPL), 1e-9);
        assertEquals(0.0, LocateArbiter.layerFactor(CodeLayer.ENTITY), 1e-9);
        double controller = LocateArbiter.layerFactor(CodeLayer.CONTROLLER);
        assertTrue(controller > 0.0 && controller < 1.0);
        assertTrue(controller < LocateArbiter.layerFactor(CodeLayer.SERVICE_IMPL));
    }

    @Test
    @DisplayName("同置信度下：Impl 必须赢过 Controller（分层约定感知）")
    void implementationLayerWinsOnSameConfidence() {
        double impl = LocateArbiter.score(0.8, 0.0, LocateArbiter.layerFactor(CodeLayer.SERVICE_IMPL));
        double controller = LocateArbiter.score(0.8, 0.0, LocateArbiter.layerFactor(CodeLayer.CONTROLLER));
        double dto = LocateArbiter.score(0.8, 0.0, LocateArbiter.layerFactor(CodeLayer.DTO));
        assertTrue(impl > controller, "实现层应优先于 Controller");
        assertTrue(controller > dto, "Controller 应优先于 DTO（DTO 是 spike 证实的过召回方向）");
    }

    @Test
    @DisplayName("置信度差 0.2 以内时分层因子可翻盘；差过大时不翻盘（避免分层压倒语义）")
    void layerDoesNotOverrideLargeConfidenceGap() {
        double implLow = LocateArbiter.score(0.5, 0.0, LocateArbiter.layerFactor(CodeLayer.SERVICE_IMPL));
        double controllerHigh = LocateArbiter.score(0.95, 0.0, LocateArbiter.layerFactor(CodeLayer.CONTROLLER));
        assertTrue(controllerHigh > implLow, "置信度差距过大时不应被分层因子翻盘");

        double implMid = LocateArbiter.score(0.70, 0.0, LocateArbiter.layerFactor(CodeLayer.SERVICE_IMPL));
        double controllerMid = LocateArbiter.score(0.68, 0.0, LocateArbiter.layerFactor(CodeLayer.CONTROLLER));
        assertTrue(implMid > controllerMid, "同档置信度下实现层应翻盘");
    }

    @Test
    @DisplayName("符号命中率：关键词命中符号名/类型名/路径才计分")
    void symbolHitRatio() {
        FileSymbolTable table = new FileSymbolTable(
                "pivotos-plugins/pivotos-plugin-message/src/main/java/com/pivotos/message/service/impl/TemplateServiceImpl.java",
                CodeLayer.SERVICE_IMPL, "TemplateServiceImpl", "模板服务", "com.pivotos.message.service.impl",
                List.of(), List.of(
                        new CodeSymbol("deleteTemplate", "method", "void deleteTemplate(Long id)", 12),
                        new CodeSymbol("render", "method", "String render(String tpl)", 30)),
                120);

        assertEquals(1.0, LocateArbiter.symbolHit(List.of("deleteTemplate"), table), 1e-9);
        assertEquals(0.5, LocateArbiter.symbolHit(List.of("deleteTemplate", "notExistSymbol"), table), 1e-9);
        assertTrue(LocateArbiter.symbolHit(List.of("TemplateServiceImpl"), table) > 0.0);
        assertEquals(0.0, LocateArbiter.symbolHit(List.of(), table), 1e-9);
        assertEquals(0.0, LocateArbiter.symbolHit(List.of("x"), null), 1e-9);
    }

    @Test
    @DisplayName("打分恒在 0~1，权重和为 1")
    void scoreBounds() {
        assertEquals(1.0, LocateArbiter.W_CONFIDENCE + LocateArbiter.W_SYMBOL_HIT + LocateArbiter.W_LAYER, 1e-9);
        double max = LocateArbiter.score(1.0, 1.0, 1.0);
        double min = LocateArbiter.score(0.0, 0.0, 0.0);
        assertEquals(1.0, max, 1e-9);
        assertEquals(0.0, min, 1e-9);
        // 越界输入被夹紧，不产出 >1 的怪分
        assertEquals(1.0, LocateArbiter.score(5.0, 5.0, 5.0), 1e-9);
    }
}
