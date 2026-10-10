package com.pivotos.starter.cloud.alibaba.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.util.ArrayList;
import java.util.List;

/**
 * Nacos 配置中心落地状态自检（V3-S2 L6）。
 *
 * <p>存在的唯一理由：{@code optional:nacos:xxx.yaml} 的兜底是<b>静默</b>的——
 * 「读到了配置」与「Nacos 不可达所以什么都没读」在日志里长得一模一样，
 * 排障时无法分辨。这里在应用就绪后把三件事一次性打出来：
 * <ol>
 *     <li>配置中心连接信息（server-addr / namespace / group）—— 确认连的是哪个域；</li>
 *     <li>环境里真实存在的 Nacos 属性源—— 没读到就是降级，读到了才是生效；</li>
 *     <li>探针值 {@code pivotos.nacos.probe}—— 真机验收时一眼看到配置内容确实下来了。</li>
 * </ol>
 *
 * <p>识别属性源刻意用<b>类名包含 nacos</b> 而不是属性源名：SCA 的属性源名是 dataId，
 * 不含 nacos 字样，按名字匹配会永远命中不了。
 */
public class NacosConfigStateLogger implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(NacosConfigStateLogger.class);

    private final ConfigurableEnvironment environment;

    public NacosConfigStateLogger(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<String> dataIds = importedDataIds();
        List<String> loaded = new ArrayList<>();
        for (PropertySource<?> ps : environment.getPropertySources()) {
            // 两种命中方式缺一不可（V3-S2 L6 实测）：SCA 落进环境的是 Boot 的 yaml 属性源，
            // 类名不含 nacos、名字也不是裸 dataId 而是 "group@dataId"（DEFAULT_GROUP@pivotos-admin-server.yaml），
            // 所以只能按「名字包含 dataId」判定；老版本 NacosPropertySource 则是类名命中。
            boolean byClass = ps.getClass().getName().toLowerCase().contains("nacos");
            boolean byName = ps.getName() != null && dataIds.stream().anyMatch(ps.getName()::contains);
            if (byClass || byName) {
                loaded.add(ps.getName() + " [" + ps.getClass().getSimpleName() + "]");
            }
        }
        log.info("[NACOS][config] 配置中心状态：server-addr={}, namespace={}, group={}, dataId={}, 已加载属性源={}, probe={}",
            environment.getProperty("spring.cloud.nacos.config.server-addr", "(未配置)"),
            environment.getProperty("spring.cloud.nacos.config.namespace", "(public)"),
            environment.getProperty("spring.cloud.nacos.config.group", "DEFAULT_GROUP"),
            dataIds.isEmpty() ? "(未声明 spring.config.import)" : String.join(", ", dataIds),
            loaded.isEmpty() ? "无（未读到任何 Nacos 配置，属正常降级）" : String.join(", ", loaded),
            environment.getProperty("pivotos.nacos.probe", "(未设置)"));
        if (log.isDebugEnabled()) {
            List<String> all = new ArrayList<>();
            environment.getPropertySources().forEach(ps -> all.add(ps.getName()));
            log.debug("[NACOS][config] 属性源全量：{}", all);
        }
    }

    /** 从 {@code spring.config.import[i]} 读出声明的 dataId（形如 optional:nacos:xxx.yaml）。 */
    private List<String> importedDataIds() {
        List<String> dataIds = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            String value = environment.getProperty("spring.config.import[" + i + "]");
            if (value == null) {
                break;
            }
            // 必须按 "nacos:" 切而不是按最后一个冒号切：环境覆盖 dataId 里还嵌着 ${...:dev} 占位符
            String resolved = environment.resolvePlaceholders(value);
            int idx = resolved.indexOf("nacos:");
            if (idx >= 0 && idx + 6 < resolved.length()) {
                dataIds.add(resolved.substring(idx + 6));
            }
        }
        return dataIds;
    }
}
