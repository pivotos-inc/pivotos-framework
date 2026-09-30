package com.pivotos.system.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 操作日志检索开关（S122）。
 * <p>默认开启：查询走 SearchTemplate（`pivotos.search.type` 决定 simple 内存实现还是 ES 实现）。
 * 关闭（{@code enabled=false}）即回到 S121 之前的 MyBatis-Plus 查询路径——零数据、零迁移的一键回滚。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Data
@ConfigurationProperties(prefix = "pivotos.search.oper-log")
public class OperLogSearchProperties {

    /** 是否走搜索抽象（false → 回退 DB 查询） */
    private boolean enabled = true;

    /** 起服时若索引为空，是否从数据库全量回灌（simple 内存实现重启即失，靠它恢复） */
    private boolean bootstrapOnStart = true;

    /** 单次回灌上限（防超大表拖慢起服；超出部分截断并 WARN） */
    private int bootstrapMaxRows = 50000;
}
