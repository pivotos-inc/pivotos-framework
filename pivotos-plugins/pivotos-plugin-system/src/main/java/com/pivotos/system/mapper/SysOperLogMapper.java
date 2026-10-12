package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysOperLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 操作日志 Mapper */
@Mapper
public interface SysOperLogMapper extends BaseMapper<SysOperLog> {

    /**
     * 物理删除（仅供留存清理使用）。
     * <p>
     * 同 {@code SysLoginLogMapper#physicalDeleteByIds} 的理由：{@code BaseDO#deleted} 带 {@code @TableLogic}，
     * {@code BaseMapper#delete*} 只置 {@code deleted=1} 不回收空间。
     * <p>
     * 注意：本表在 ES 侧有索引副本（S122 双写），删除必须由调用方
     * （{@code OperLogCleanupJob}）同步调 {@code OperLogSearchSupport#deleteByIds}，
     * 否则检索会命中已删数据——本方法只管库表，不管索引。
     *
     * @param ids 待删主键
     * @return 实际删除行数
     */
    @Delete("<script>DELETE FROM sys_oper_log WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int physicalDeleteByIds(@Param("ids") List<Long> ids);
}
