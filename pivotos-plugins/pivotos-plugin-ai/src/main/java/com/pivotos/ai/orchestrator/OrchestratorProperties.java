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

    /**
     * A5-2 重试策略（S117）。
     *
     * <p>写步骤默认零重试：写工具（发站内信 / 催办）非幂等且守卫把异常吞成文本，
     * 无法判定失败发生在副作用之前还是之后，重试会实实在在重复副作用。
     */
    private Retry retry = new Retry();

    /** A5-2 熔断（S117）：限制单条计划在故障态下继续消耗工具调用 */
    private Circuit circuit = new Circuit();

    @Getter
    @Setter
    public static class Retry {

        /** 重试是否启用（默认开：只读步骤的瞬时故障值得再试一次） */
        private boolean enabled = true;

        /** 单步最大尝试次数（含首次；1 表示不重试） */
        private int maxAttempts = 2;

        /**
         * 写步骤是否允许重试（默认 false）——非幂等写操作重试会重复副作用。
         * 打开前必须确认目标工具自身幂等，当前编排面工具无一满足。
         */
        private boolean writeEnabled = false;

        /** 重试间隔（毫秒；0 表示不等待。仅在重试时等待，不阻塞正常链路） */
        private long backoffMs = 300L;
    }

    @Getter
    @Setter
    public static class Circuit {

        /** 熔断是否启用 */
        private boolean enabled = true;

        /**
         * 单条计划的重试预算（累计重试次数上限）。
         *
         * <p>为什么是「重试预算」而不是「连续失败步骤数」：编排是失败即停的顺序链，
         * 一次执行最多只有一个失败步骤，「连续失败 N 步」永远达不到；
         * 真正需要兜住的是「在故障态下反复重试把调用量放大」，因此阈值作用于累计重试次数。
         */
        private int maxRetriesPerPlan = 3;
    }
}
