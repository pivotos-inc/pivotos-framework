package com.pivotos.ai.coding.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * A4-1 代码定位配置（{@code pivotos.ai.coding.locate.*}）。
 *
 * <p>默认关闭：定位能力要扫仓库源码，未登记仓库时不应静默生效（避免 dev 之外环境误扫目录）。
 *
 * @author PivotOS
 * @since 2.14.0（S110 A4-1）
 */
@Data
@ConfigurationProperties(prefix = "pivotos.ai.coding.locate")
public class LocateProperties {

    /** 定位能力总开关 */
    private boolean enabled = false;

    /** 粗筛候选数（LLM 粗筛 top-N） */
    private int topN = 5;

    /** 确定性关键词召回条数（与 LLM 粗筛取并集，保证召回下限） */
    private int keywordTopK = 5;

    /** 进入精定位的候选上限（控制并行调用成本） */
    private int maxCandidates = 8;

    /** 模型覆盖（留空走供应商默认模型） */
    private String model = "";

    /** 采样温度（定位要稳，默认低温） */
    private double temperature = 0.2;

    /** 精定位喂入文件的最大行数（超出截断，防超大文件打爆上下文） */
    private int maxFeedLines = 800;

    /** 登记的可定位仓库 */
    private List<RepoConfig> repos = new ArrayList<>();

    /**
     * 单个可定位仓库配置。
     */
    @Data
    public static class RepoConfig {

        /** 仓库逻辑名（请求中的 repo 字段） */
        private String name;

        /** 仓库根绝对路径 */
        private String root;

        /** 相对根的路径通配（glob：** 跨目录，* 单段） */
        private List<String> includes = new ArrayList<>();
    }

    /** 按逻辑名取仓库配置 */
    public RepoConfig repo(String name) {
        if (name == null || repos == null) {
            return null;
        }
        for (RepoConfig config : repos) {
            if (name.equals(config.name)) {
                return config;
            }
        }
        return null;
    }
}
