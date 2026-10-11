package com.pivotos.system.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.system.domain.entity.SysLoginLog;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 登录日志 Mapper */
@Mapper
public interface SysLoginLogMapper extends BaseMapper<SysLoginLog> {

    /**
     * 物理删除（仅供留存清理使用）。
     * <p>
     * 为什么必须显式写 {@code DELETE}：{@code BaseDO#deleted} 带 {@code @TableLogic}，
     * {@code BaseMapper} 的 {@code delete*()} 会被 MP 改写成 {@code UPDATE ... SET deleted=1}，
     * <strong>行数不变、空间不回收</strong>——留存策略用了等于没用。
     * 故留存清理走本方法。调用方（RetentionCleanupTemplate）已提供保留天数硬下限、
     * 单次删除上限与 dry-run 三道安全带。
     *
     * @param ids 待删主键（非空，由「先按时间取主键」得到）
     * @return 实际删除行数
     */
    @Delete("<script>DELETE FROM sys_login_log WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int physicalDeleteByIds(@Param("ids") List<Long> ids);
}
