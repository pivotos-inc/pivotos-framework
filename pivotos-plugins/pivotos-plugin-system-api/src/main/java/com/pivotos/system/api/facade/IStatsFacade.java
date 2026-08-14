package com.pivotos.system.api.facade;

import com.pivotos.system.api.dto.SystemStatsDTO;
import com.pivotos.system.api.dto.TrendPointDTO;

import java.util.List;

/**
 * 系统运营统计门面（跨 Plugin 契约，S71 运营工作台/数据大屏）。
 */
public interface IStatsFacade {

    /** 系统基础计数（用户/角色/部门/岗位 + 今日成功登录数） */
    SystemStatsDTO systemStats();

    /** 近 N 日每日登录趋势（成功登录，缺日补 0） */
    List<TrendPointDTO> loginTrend(int days);
}
