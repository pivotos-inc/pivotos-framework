package com.pivotos.starter.job.retention;

import java.util.List;

/**
 * 留存清理的持久化动作（由各插件按自己的 Mapper 实现）。
 * <p>
 * 刻意设计成「先按尺寸取主键、再按主键删」两步，而不是「直接按时间 DELETE」：
 * <ol>
 *   <li>能施加<b>单次上限</b>（取多少删多少，剩下的下一轮再取）；</li>
 *   <li>能<b>先统计后删除</b>，让 dry-run 与实际执行走完全相同的查询路径；</li>
 *   <li>按主键删是<b>快照语义</b>：取 id 之后新写入的行不在集合内，不会被误删。</li>
 * </ol>
 * 两个方法都<strong>在已绑定好上下文的作用域内</strong>被调用：
 * 租户表的 {@code loadExpiredIds} 会命中租户行级过滤，取到的 id 天然属于当前租户——
 * 即便 {@code deleteByIds} 的 SQL 本身没写租户条件，删的也只是本租户的行（双保险）。
 *
 * @author PivotOS
 * @since 3.0.0
 */
public interface RetentionPurgeOps {

    /**
     * 取一批待删主键。
     *
     * @param size 本批最大条数（由 {@code max-delete-per-run} 传入，恒 ≥ 1）
     * @return 主键批次 + 待删总数
     */
    RetentionIdBatch loadExpiredIds(int size);

    /**
     * 物理删除指定主键，返回实际删除行数。
     */
    int deleteByIds(List<Long> ids);
}
