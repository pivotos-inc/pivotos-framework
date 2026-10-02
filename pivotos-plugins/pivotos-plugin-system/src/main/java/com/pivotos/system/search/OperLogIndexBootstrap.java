package com.pivotos.system.search;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.mapper.SysOperLogMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 操作日志索引冷启动回灌（S122）。
 *
 * <p>触发口径：<b>仅在索引为空时</b>从数据库全量回灌一次——
 * simple 内存实现重启即失，靠它把历史数据补回来；ES 实现下索引已存在则不重复刷（避免每次起服打爆集群）。
 * 需要强制重建时，清掉索引后重启即可（或接 ES 后用其自身的 reindex 手段）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Component
public class OperLogIndexBootstrap {

    private static final Logger log = LoggerFactory.getLogger(OperLogIndexBootstrap.class);

    private final SysOperLogMapper operLogMapper;
    private final OperLogSearchSupport support;

    public OperLogIndexBootstrap(SysOperLogMapper operLogMapper, OperLogSearchSupport support) {
        this.operLogMapper = operLogMapper;
        this.support = support;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrap() {
        // 回灌是旁路：任何异常都不能把应用拖到起不来（起服失败比「检索少数据」严重得多）
        try {
            doBootstrap();
        } catch (Exception e) {
            log.warn("[PivotOS][search] 操作日志索引回灌失败（不影响起服）：reason={}", e.getMessage());
        }
    }

    private void doBootstrap() {
        if (!support.enabled()) {
            log.info("[PivotOS][search] 操作日志检索未开启（pivotos.search.oper-log.enabled=false），跳过索引回灌");
            return;
        }
        if (!support.isBootstrapOnStart()) {
            return;
        }
        long indexed = support.countIndexed();
        if (indexed > 0) {
            log.info("[PivotOS][search] 操作日志索引已有 {} 条，跳过回灌", indexed);
            return;
        }
        if (indexed < 0) {
            log.warn("[PivotOS][search] 操作日志索引计数不可用，跳过回灌");
            return;
        }
        int maxRows = Math.max(support.getBootstrapMaxRows(), 1);
        long total = operLogMapper.selectCount(Wrappers.<SysOperLog>lambdaQuery());
        if (total > maxRows) {
            log.warn("[PivotOS][search] 操作日志 {} 条超出回灌上限 {}，本次仅回灌最近 {} 条",
                    total, maxRows, maxRows);
        }
        Page<SysOperLog> page = operLogMapper.selectPage(
                new Page<>(1, maxRows),
                Wrappers.<SysOperLog>lambdaQuery().orderByDesc(SysOperLog::getOperTime));
        List<SysOperLog> records = page == null ? null : page.getRecords();
        if (records == null || records.isEmpty()) {
            log.info("[PivotOS][search] 操作日志无历史数据，索引回灌跳过");
            return;
        }
        support.indexBatch(records);
    }
}
