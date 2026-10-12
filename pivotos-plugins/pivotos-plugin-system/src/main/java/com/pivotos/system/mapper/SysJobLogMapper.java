package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysJobLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 定时任务执行记录 Mapper */
@Mapper
public interface SysJobLogMapper extends BaseMapper<SysJobLog> {

    /**
     * 物理删除（仅供留存清理使用）。
     * <p>
     * 同 {@code SysLoginLogMapper#physicalDeleteByIds}：{@code BaseDO#deleted} 带 {@code @TableLogic}，
     * {@code BaseMapper#delete*} 只是置 {@code deleted=1}，空间不回收，留存清理必须显式 {@code DELETE}。
     * <p>
     * <b>自指说明</b>：本表就是「任务执行记录」表，清理任务本身也会往里写一条。
     * 顺序是「先删历史、后写本次」，本次记录在删除之后写入，不会被自己删掉；
     * 再叠加保留天数硬下限（默认 ≥ 1 天），自指不会演变成自杀。
     *
     * @param ids 待删主键
     * @return 实际删除行数
     */
    @Delete("<script>DELETE FROM sys_job_log WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int physicalDeleteByIds(@Param("ids") List<Long> ids);
}
