package com.pivotos.system.api.dto;

import lombok.Data;

/**
 * 系统运营统计 DTO（跨 Plugin 契约，运营工作台/数据大屏用，S71）。
 */
@Data
public class SystemStatsDTO {

    /** 用户总数 */
    private Long userCount;

    /** 角色总数 */
    private Long roleCount;

    /** 部门总数 */
    private Long deptCount;

    /** 岗位总数 */
    private Long postCount;

    /** 今日登录次数（成功） */
    private Long todayLogins;
}
