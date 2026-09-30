package com.pivotos.ai.tool;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.service.AiToolService;
import com.pivotos.common.core.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE 入口帧序单元测试（S118 AI-2 四入口）。
 *
 * <p>锁死三件事：① 成功与失败都必有 done 收尾；② 失败落成 error 帧且带 code（不外抛）；
 * ③ 入参缺省归一为 {}（与 REST 入口同口径，否则守卫侧的 JSON 解析会出现两套行为）。
 */
class ToolCallStreamerTest {

    private final AiToolService toolService = mock(AiToolService.class);

    private List<ToolCallStreamer.Frame> runTool(String argsJson) {
        ToolCallStreamer streamer = new ToolCallStreamer(toolService);
        List<ToolCallStreamer.Frame> frames = new ArrayList<>();
        streamer.stream("queryMyPendingTaskCount", argsJson, frames::add);
        return frames;
    }

    private static List<String> names(List<ToolCallStreamer.Frame> frames) {
        return frames.stream().map(ToolCallStreamer.Frame::name).toList();
    }

    @Test
    void successEmitsMetaResultDone() {
        when(toolService.invokeTool(eq("queryMyPendingTaskCount"), anyString())).thenReturn("7");

        List<ToolCallStreamer.Frame> frames = runTool("{\"confirm\":true}");

        assertThat(names(frames)).containsExactly("meta", "result", "done");
        assertThat(frames.get(1).data()).isEqualTo("7");
        Map<?, ?> meta = (Map<?, ?>) frames.get(0).data();
        assertThat(meta.get("toolName")).isEqualTo("queryMyPendingTaskCount");
        assertThat(meta.get("timestamp")).isNotNull();
    }

    @Test
    void unregisteredToolEmitsErrorFrameWithCodeInsteadOfThrowing() {
        when(toolService.invokeTool(eq("queryMyPendingTaskCount"), anyString()))
                .thenThrow(new ServiceException(AiErrorCode.AI_TOOL_NOT_FOUND));

        List<ToolCallStreamer.Frame> frames = runTool(null);

        assertThat(names(frames)).containsExactly("meta", "error", "done");
        Map<?, ?> error = (Map<?, ?>) frames.get(1).data();
        assertThat(error.get("code")).isEqualTo(AiErrorCode.AI_TOOL_NOT_FOUND.getCode());
        assertThat(error.get("msg")).isEqualTo(AiErrorCode.AI_TOOL_NOT_FOUND.getMsg());
    }

    @Test
    void unexpectedExceptionFallsBackToStreamFailedCode() {
        when(toolService.invokeTool(eq("queryMyPendingTaskCount"), anyString()))
                .thenThrow(new IllegalStateException("boom"));

        List<ToolCallStreamer.Frame> frames = runTool("{}");

        assertThat(names(frames)).containsExactly("meta", "error", "done");
        Map<?, ?> error = (Map<?, ?>) frames.get(1).data();
        assertThat(error.get("code")).isEqualTo(AiErrorCode.AI_TOOL_STREAM_FAILED.getCode());
        assertThat((String) error.get("msg")).contains("boom");
    }

    @Test
    void blankArgsNormalizedToEmptyJson() {
        when(toolService.invokeTool(eq("queryMyPendingTaskCount"), anyString())).thenReturn("0");

        runTool("   ");

        verify(toolService).invokeTool("queryMyPendingTaskCount", "{}");
    }
}
