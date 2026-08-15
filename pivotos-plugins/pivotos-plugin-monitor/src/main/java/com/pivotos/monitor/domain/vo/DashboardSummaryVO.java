package com.pivotos.monitor.domain.vo;

import com.pivotos.ai.api.dto.AiChatStatsDTO;
import com.pivotos.ai.kb.api.dto.KbStatsDTO;
import com.pivotos.file.api.dto.FileStatsDTO;
import com.pivotos.system.api.dto.SystemStatsDTO;
import com.pivotos.system.api.dto.TrendPointDTO;
import com.pivotos.workflow.api.dto.WorkflowStatsDTO;
import lombok.Data;

import java.util.List;

/**
 * 运营工作台/数据大屏聚合 VO（S71）：各区块由各 Plugin Facade 汇总，
 * 区块为 null 表示对应插件统计失败降级，前端跳过渲染即可。
 */
@Data
public class DashboardSummaryVO {

    /** 在线用户数（Redis 会话扫描） */
    private Long onlineUsers;

    /** 系统基础统计（用户/角色/部门/岗位/今日登录 + 登录趋势在 loginTrend） */
    private SystemStatsDTO system;

    /** 近 7 日登录趋势 */
    private List<TrendPointDTO> loginTrend;

    /** 工作流实例统计 */
    private WorkflowStatsDTO workflow;

    /** 文件存储统计 */
    private FileStatsDTO file;

    /** AI 运营统计（含近 7 日消息趋势与 Key 健康） */
    private AiChatStatsDTO ai;

    /** 知识库规模统计 */
    private KbStatsDTO kb;
}
