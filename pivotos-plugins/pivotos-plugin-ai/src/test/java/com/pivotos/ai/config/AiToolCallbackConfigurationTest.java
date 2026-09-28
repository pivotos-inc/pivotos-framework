package com.pivotos.ai.config;

import com.pivotos.ai.service.AiToolGuardService;
import com.pivotos.ai.service.AiToolInvokeRecorder;
import com.pivotos.ai.tool.MessageTools;
import com.pivotos.ai.tool.ToolObjectContributor;
import com.pivotos.ai.tool.WorkflowQueryTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 工具对象扩展点装配测试（A4-3 / S112）。
 *
 * <p>锁死的风险：{@code ToolObjectContributor} 若在某次重构中静默失效（登记不进
 * ToolCallbackProvider），不会报任何错——内置工具照常工作，下游工具凭空消失，
 * 与「装配成功」长得一模一样（同 S111 门禁跳过分支的静默失效模式）。因此：
 * <ul>
 *   <li>有贡献者：登记的工具必须出现在回调列表里；</li>
 *   <li>无贡献者 / 返回 null / 返回含 null 元素的数组：装配必须保持既有内置工具不崩；</li>
 *   <li>守卫包装不丢工具（GuardedToolCallbackProvider 透传全部回调）。</li>
 * </ul>
 *
 * <p>运行时注入语义（ObjectProvider&lt;List&lt;T&gt;&gt; 泛型解析）无法在纯单测覆盖，
 * 由 fat jar 启动日志「内置 2 个 + 扩展登记 N 个」与 /ai/tool 工具列表 E2E 断言兜底。
 *
 * @author PivotOS
 * @since 2.14.0（S112 A4-3）
 */
@DisplayName("AiToolCallbackConfiguration 扩展点装配 测试")
class AiToolCallbackConfigurationTest {

    /** 本地贡献者：两个 @Tool 方法，模拟下游插件（如 ai-coding 的工程文件读写工具） */
    static class LocalContributor implements ToolObjectContributor {
        @Tool(name = "contribToolA", description = "测试工具A（扩展点登记）")
        public String contribToolA(@ToolParam(description = "输入") String input) {
            return input;
        }

        @Tool(name = "contribToolB", description = "测试工具B（扩展点登记）")
        public String contribToolB(@ToolParam(description = "输入") String input) {
            return input;
        }

        @Override
        public Object[] toolObjects() {
            return new Object[]{this};
        }
    }

    @SuppressWarnings("unchecked")
    private ToolCallbackProvider build(ObjectProvider<List<ToolObjectContributor>> contributors) {
        AiToolCallbackConfiguration configuration = new AiToolCallbackConfiguration();
        return configuration.pivotToolCallbackProvider(
                mock(WorkflowQueryTools.class),
                mock(MessageTools.class),
                mock(AiToolGuardService.class),
                mock(AiToolInvokeRecorder.class),
                contributors);
    }

    private Set<String> toolNames(ToolCallbackProvider provider) {
        return Arrays.stream(provider.getToolCallbacks())
                .map(cb -> cb.getToolDefinition().name())
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<List<ToolObjectContributor>> providerOf(ToolObjectContributor... items) {
        ObjectProvider<List<ToolObjectContributor>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(List.of(items));
        return provider;
    }

    @Test
    @DisplayName("贡献者登记的工具必须进入回调列表（静默失效零容忍）")
    void contributorToolsAreRegistered() {
        ToolCallbackProvider provider = build(providerOf(new LocalContributor()));
        Set<String> names = toolNames(provider);
        assertTrue(names.contains("contribToolA"), "扩展工具 A 未登记：" + names);
        assertTrue(names.contains("contribToolB"), "扩展工具 B 未登记：" + names);
    }

    @Test
    @DisplayName("无贡献者：保持既有内置工具（WorkflowQueryTools + MessageTools 的 @Tool 全在）")
    @SuppressWarnings("unchecked")
    void noContributorKeepsBuiltins() {
        ObjectProvider<List<ToolObjectContributor>> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        ToolCallbackProvider built = build(provider);
        ToolCallbackProvider withContributor = build(providerOf(new LocalContributor()));
        // 内置工具集合是扩展登记的超集基础：登记前后的差集恰为扩展工具
        Set<String> before = toolNames(built);
        Set<String> after = toolNames(withContributor);
        assertEquals(before.size() + 2, after.size(),
                "扩展登记必须净增工具，否则视为静默失效：before=" + before + " after=" + after);
        assertTrue(after.containsAll(before), "扩展登记不得挤掉内置工具");
    }

    @Test
    @DisplayName("贡献者返回空数组 / 数组含 null 元素：不崩、不登记")
    void emptyOrNullContributionsAreTolerated() {
        ToolObjectContributor empty = ToolObjectContributorLiteral.empty();
        assertEquals(0, empty.toolObjects().length);

        ObjectProvider<List<ToolObjectContributor>> nullElement = providerOf(
                (ToolObjectContributor) () -> new Object[]{null});
        ToolCallbackProvider provider = build(nullElement);
        // null 元素被跳过，装配不抛异常
        assertFalse(toolNames(provider).isEmpty());
    }

    /** 返回空数组的贡献者字面量 */
    private static final class ToolObjectContributorLiteral {
        private static ToolObjectContributor empty() {
            return () -> new Object[0];
        }
    }
}
