package com.pivotos.workflow.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 流程历史任务（warm-flow 引擎自带表 {@code flow_his_task}）的<strong>物理删除专用 Mapper</strong>
 * （v3.1.0 P1-B1 新增）。
 *
 * <h3>两个「为什么」都是踩出来的</h3>
 * <ol>
 *   <li><b>为什么类名不叫 {@code FlowHisTaskMapper}</b>：warm-flow 自带
 *       {@code org.dromara.warm.flow.orm.mapper.FlowHisTaskMapper}（{@code WarmMapper<FlowHisTask>}
 *       extends {@code BaseMapper}），它已经被 MP 注册成 bean {@code flowHisTaskMapper}。
 *       本仓再建一个同名接口会撞 bean 名，启动直接
 *       {@code ConflictingBeanDefinitionException}——**是启动期崩溃，不是运行期小问题**。
 *       故本类只做一件事（删除），名字也写明职责。</li>
 *   <li><b>为什么不直接用引擎 Mapper 的 {@code delete*}</b>：引擎实体
 *       {@code org.dromara.warm.flow.orm.entity.FlowHisTask} 的 {@code delFlag} 带
 *       {@code @TableLogic}（javap 反编译 1.8.9 实证），{@code deleteBatchIds} 会被改写成
 *       {@code UPDATE ... SET del_flag=...}——<strong>行数不变、空间不回收</strong>。
 *       留存清理要的是回收空间，必须显式 {@code DELETE}。</li>
 * </ol>
 *
 * <b>查询侧不在这里</b>：取待删主键复用引擎自带的 {@code FlowHisTaskMapper#selectPage}，
 * 与引擎共用同一套实体映射，避免本仓再维护一份会漂移的表结构投影。
 *
 * @author PivotOS
 * @since 3.0.0
 */
@Mapper
public interface FlowHisTaskRetentionMapper {

    /**
     * 物理删除（仅供留存清理使用）。
     * <p>
     * 租户条件由 MP 的 {@code TenantLineInnerInterceptor} 追加（本表有 {@code tenant_id} 列且不在
     * 租户内置忽略表内）；再加上「主键本身是带租户过滤查出来的」这一层，双重保险。
     * 调用方必须逐租户执行（{@code RetentionCleanupTemplate#runPerTenant}）。
     *
     * @param ids 待删主键
     * @return 实际删除行数
     */
    @Delete("<script>DELETE FROM flow_his_task WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int physicalDeleteByIds(@Param("ids") List<Long> ids);
}
