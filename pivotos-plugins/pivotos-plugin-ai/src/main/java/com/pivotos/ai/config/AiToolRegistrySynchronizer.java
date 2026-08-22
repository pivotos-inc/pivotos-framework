package com.pivotos.ai.config;

import com.pivotos.ai.service.AiToolService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * AI 工具注册表启动同步器（S98 A2）
 *
 * <p>应用就绪后把 @Tool 扫描结果 upsert 进 ai_tool（Flyway 已先行建表），
 * 保证守卫层的「未注册拒绝」闸有数据可依。同步失败只记 WARN 不阻断启动
 * （工具链路降级为全拒，属可控失败面）。
 */
@Component
@RequiredArgsConstructor
public class AiToolRegistrySynchronizer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AiToolRegistrySynchronizer.class);

    private final AiToolService aiToolService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            aiToolService.syncRegisteredTools();
        } catch (Exception e) {
            log.warn("[PivotOS] AI 工具注册表同步失败（工具调用将按未注册口径全拒）：{}", e.getMessage());
        }
    }
}
