package com.pivotos.monitor.service;

import com.pivotos.ai.api.facade.IAiFacade;
import com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade;
import com.pivotos.file.api.facade.IFileFacade;
import com.pivotos.monitor.domain.vo.DashboardSummaryVO;
import com.pivotos.system.api.facade.IStatsFacade;
import com.pivotos.workflow.api.IWorkflowFacade;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.function.Function;

/**
 * 运营工作台/数据大屏聚合服务（S71）。
 *
 * <p>各区块经跨插件 Facade 契约采集；任一区块失败仅降级为 null，不阻断整体。
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final Logger log = LoggerFactory.getLogger(DashboardService.class);

    /** 与 system 插件 OnlineUserService 同一 Redis key 前缀（登录会话） */
    private static final String TOKEN_SESSION_PREFIX = "Authorization:sys-user:token-session:";

    /** 趋势天数：近 7 日 */
    private static final int TREND_DAYS = 7;

    private final RedissonClient redissonClient;
    private final ObjectProvider<IStatsFacade> statsFacade;
    private final ObjectProvider<IWorkflowFacade> workflowFacade;
    private final ObjectProvider<IFileFacade> fileFacade;
    private final ObjectProvider<IAiFacade> aiFacade;
    private final ObjectProvider<IKnowledgeBaseFacade> knowledgeBaseFacade;

    /** 聚合全部区块（工作台与数据大屏共用） */
    public DashboardSummaryVO summary() {
        DashboardSummaryVO vo = new DashboardSummaryVO();
        vo.setOnlineUsers(countOnlineUsers());
        IStatsFacade stats = statsFacade.getIfAvailable();
        vo.setSystem(safe("system", stats, IStatsFacade::systemStats));
        vo.setLoginTrend(safe("loginTrend", stats, f -> f.loginTrend(TREND_DAYS)));
        vo.setWorkflow(safe("workflow", workflowFacade.getIfAvailable(), IWorkflowFacade::instanceStats));
        vo.setFile(safe("file", fileFacade.getIfAvailable(), IFileFacade::storageStats));
        vo.setAi(safe("ai", aiFacade.getIfAvailable(), f -> f.chatStats(TREND_DAYS)));
        vo.setKb(safe("kb", knowledgeBaseFacade.getIfAvailable(), IKnowledgeBaseFacade::kbStats));
        return vo;
    }

    /** 区块采集兜底：facade 缺失或异常时返回 null（前端跳过该区块渲染） */
    private <T, R> R safe(String block, T facade, Function<T, R> collector) {
        if (facade == null) {
            return null;
        }
        try {
            return collector.apply(facade);
        } catch (Exception e) {
            log.warn("[PivotOS] 运营看板区块 {} 采集失败，已降级：{}", block, e.getMessage());
            return null;
        }
    }

    /** 在线用户数：Redis 登录会话 key 计数（pattern 口径与 system 插件 OnlineUserService 一致） */
    private long countOnlineUsers() {
        try {
            long count = 0L;
            for (String ignored : redissonClient.getKeys().getKeysByPattern(TOKEN_SESSION_PREFIX + "*")) {
                count++;
            }
            return count;
        } catch (Exception e) {
            log.warn("[PivotOS] 在线用户数采集失败：{}", e.getMessage());
            return 0L;
        }
    }
}
