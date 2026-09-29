package com.pivotos.ai.orchestrator;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * A5-1 工具多步编排配置（S116）。
 *
 * <p>入口：<code>pivotos.ai.orchestrator.*</code>。默认收敛——
 * 编排是「能发起写操作」的能力，默认只开一维 Lists，且把写工程源码的工具排除在外。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "pivotos.ai.orchestrator")
public class OrchestratorProperties {

    /** 编排是否启用（默认关闭：与 A4 定位能力一致的「能力显式开启」口径） */
    private boolean enabled = false;

    /** 单条计划的步骤上限（防模型产出失控长链） */
    private int maxSteps = 10;

    /** 意图最大长度 */
    private int maxIntentLength = 500;

    /** 不参与编排的工具名（越权/高危工具默认排除） */
    private List<String> excludedTools = new ArrayList<>(List.of("writeCodeFile"));

    /** 规划使用的模型（留空取供应商默认模型） */
    private String model = "";

    /** 计划 JSON 入库前的最大字符数（超长截断，防 LLM 吐长文） */
    private int maxPlanJsonLength = 6000;
}
