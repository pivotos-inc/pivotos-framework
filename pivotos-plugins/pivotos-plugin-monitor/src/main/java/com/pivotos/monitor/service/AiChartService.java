package com.pivotos.monitor.service;

import com.pivotos.ai.api.enums.AiErrorCode;
import com.pivotos.ai.api.facade.IAiFacade;
import com.pivotos.ai.api.usage.AiUsageContext;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.monitor.domain.vo.AiChartSpecVO;
import com.pivotos.monitor.domain.vo.DashboardSummaryVO;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 生成图表服务（S72 PL-REPORT 二期）。
 *
 * <p>数据源红线：LLM 只消费 {@link DashboardService#summary()} 的实时快照，不做任意取数；
 * 产物红线：LLM 只输出结构化 ChartSpec，经白名单校验后交前端确定性装配 ECharts option。
 */
@Service
@RequiredArgsConstructor
public class AiChartService {

    private static final Logger log = LoggerFactory.getLogger(AiChartService.class);

    /** 图表类型白名单 */
    private static final Set<String> CHART_TYPES = Set.of("line", "bar", "pie");

    /** 单图系列数上限（防 LLM 输出失控） */
    private static final int MAX_SERIES = 6;

    /** 单系列数据点上限 */
    private static final int MAX_POINTS = 60;

    /**
     * 权威数据字典（S73 F1）：状态码语义等口径说明喂入 prompt，杜绝 LLM 臆断。
     * 工作流状态码与前端 FLOW_STATUS_NAMES（home/bigscreen）同源同值，修改须两处同步。
     */
    private static final String DATA_DICTIONARY = """
            Data dictionary (authoritative semantics, MUST be used when labeling categories):
            - workflow.statusCounts keys are warmflow status codes:
              0=待提交, 1=审批中, 2=审批通过, 4=终止, 5=作废, 6=撤销, 8=已完成, 9=已退回, 10=失效, 11=拿回
            - workflow.pendingTasks = 待办任务数（待当前用户处理的任务，不是流程实例状态）
            - system.todayLogins = 今日成功登录次数；loginTrend 按日统计成功登录数
            - ai.activeKeyCount = 启用中的 API Key 数；ai.unhealthyKeyCount = 启用中但连续失败计数>0 的 Key 数
            - file.totalBytes 单位为字节
            Never guess the meaning of status codes outside this dictionary.
            """;

    private static final String SYSTEM_PROMPT = """
            You are a data visualization assistant for the PivotOS operations dashboard.
            You receive a JSON snapshot of platform operational statistics and a user request in Chinese.
            Decide how to visualize ONLY the given data and output a chart spec in JSON.
            Rules:
            1. Use ONLY values present in the given data; never invent or estimate numbers.
            2. chartType must be exactly one of: line, bar, pie.
            3. title: concise Chinese chart title.
            4. categories: array of category labels for the x-axis; required for line/bar, omit for pie.
            5. series: array of {name: short Chinese series name, data: array of numbers}.
               For line/bar every series.data must have the same length as categories.
               For pie output exactly one series whose data align with categories.
            6. explanation: one short Chinese sentence describing what the chart shows.
            7. When labeling categories or series derived from status codes or dictionary keys,
               you MUST use the authoritative names from the data dictionary provided with the data;
               never invent your own interpretation of codes.
            8. If the request cannot be answered with the given data, still pick the closest
               reasonable visualization of the available data and mention the limitation in explanation.
            Output ONLY JSON, no markdown:
            {"title":"...","chartType":"line|bar|pie","categories":[...],"series":[{"name":"...","data":[...]}],"explanation":"..."}
            """;

    private final DashboardService dashboardService;
    private final ObjectProvider<IAiFacade> aiFacade;
    private final ObjectMapper objectMapper;

    /** 自然语言 → ChartSpec（summary 快照采集 + LLM 推断 + 白名单校验） */
    public AiChartSpecVO generate(String question) {
        if (question == null || question.isBlank()) {
            throw new ServiceException(AiErrorCode.CHART_QUESTION_EMPTY);
        }
        IAiFacade facade = aiFacade.getIfAvailable();
        if (facade == null) {
            throw new ServiceException(AiErrorCode.AI_NOT_CONFIGURED);
        }

        DashboardSummaryVO summary = dashboardService.summary();
        String dataContext = objectMapper.writeValueAsString(summary);
        String userPrompt = "Operational statistics snapshot:\n" + dataContext
                + "\n\n" + DATA_DICTIONARY
                + "\nUser request: " + question.trim() + "\n\nOutput JSON chart spec:";

        // S92：chart 场景计量（facade 内部走动态 Key 体系时由包装层落 usage）
        String response = AiUsageContext.callWithScene(AiUsageContext.SCENE_CHART,
                () -> facade.chatWithSystem(SYSTEM_PROMPT, userPrompt));
        if (response == null || response.isBlank()) {
            throw new ServiceException(AiErrorCode.CHART_GEN_FAILED);
        }
        return parseAndValidate(response);
    }

    /** 剥 markdown 围栏 → JSON 解析 → 白名单校验（任一不合法统一 5004 提示重新描述） */
    private AiChartSpecVO parseAndValidate(String response) {
        String json = response.trim();
        if (json.startsWith("```")) {
            int start = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            json = (start >= 0 && end > start) ? json.substring(start + 1, end).trim() : json;
        }

        Map<String, Object> raw;
        try {
            raw = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("[PivotOS] AI 图表输出非合法 JSON：{}", response);
            throw new ServiceException(AiErrorCode.CHART_GEN_FAILED);
        }

        String chartType = asString(raw.get("chartType"));
        if (chartType == null || !CHART_TYPES.contains(chartType)) {
            log.warn("[PivotOS] AI 图表类型不在白名单：{}", chartType);
            throw new ServiceException(AiErrorCode.CHART_GEN_FAILED);
        }

        List<String> categories = toStringList(raw.get("categories"));
        List<AiChartSpecVO.Series> series = toSeriesList(raw.get("series"));
        if (series.isEmpty()) {
            throw new ServiceException(AiErrorCode.CHART_GEN_FAILED);
        }

        boolean pie = "pie".equals(chartType);
        if (!pie) {
            // line/bar：类目必填，且各系列数据与类目等长
            if (categories.isEmpty()) {
                throw new ServiceException(AiErrorCode.CHART_GEN_FAILED);
            }
            for (AiChartSpecVO.Series s : series) {
                if (s.getData().size() != categories.size()) {
                    throw new ServiceException(AiErrorCode.CHART_GEN_FAILED);
                }
            }
        } else if (categories.isEmpty() && !series.get(0).getData().isEmpty()) {
            // pie 兜底：无类目时用「分类 N」占位，保证可渲染
            int size = series.get(0).getData().size();
            categories = new ArrayList<>(size);
            for (int i = 1; i <= size; i++) {
                categories.add("分类" + i);
            }
        }

        AiChartSpecVO vo = new AiChartSpecVO();
        vo.setTitle(asString(raw.get("title")));
        vo.setChartType(chartType);
        vo.setCategories(categories);
        vo.setSeries(series);
        vo.setExplanation(asString(raw.get("explanation")));
        return vo;
    }

    private List<AiChartSpecVO.Series> toSeriesList(Object raw) {
        List<AiChartSpecVO.Series> result = new ArrayList<>();
        if (!(raw instanceof List<?> list)) {
            return result;
        }
        for (Object item : list) {
            if (result.size() >= MAX_SERIES || !(item instanceof Map<?, ?> map)) {
                continue;
            }
            List<Double> data = new ArrayList<>();
            Object rawData = map.get("data");
            if (rawData instanceof List<?> dataList) {
                for (Object value : dataList) {
                    if (data.size() >= MAX_POINTS) {
                        break;
                    }
                    Double num = toDouble(value);
                    if (num == null) {
                        // 系列内出现非数值即视为整段不可信
                        data.clear();
                        break;
                    }
                    data.add(num);
                }
            }
            if (data.isEmpty()) {
                continue;
            }
            AiChartSpecVO.Series series = new AiChartSpecVO.Series();
            String name = asString(map.get("name"));
            series.setName(name == null || name.isBlank() ? "系列" + (result.size() + 1) : name);
            series.setData(data);
            result.add(series);
        }
        return result;
    }

    private List<String> toStringList(Object raw) {
        List<String> result = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (result.size() >= MAX_POINTS) {
                    break;
                }
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
        }
        return result;
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Double.parseDouble(text.replace(",", ""));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
