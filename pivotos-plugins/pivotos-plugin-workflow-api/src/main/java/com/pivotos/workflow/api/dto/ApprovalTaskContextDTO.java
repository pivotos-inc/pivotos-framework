package com.pivotos.workflow.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 审批任务上下文传输对象（S101 A3 AI 审批助手：待办聚合供给面）
 *
 * <p>一条待办任务的完整审批上下文：实例信息 + 流程变量 + 审批历史，
 * 供跨插件消费方（AI 审批建议生成）聚合申请单内容。
 * 契约层禁框架依赖，字段独立声明。
 */
@Data
public class ApprovalTaskContextDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 任务 ID */
    private Long taskId;

    /** 流程实例 ID */
    private Long instanceId;

    /** 流程名称 */
    private String flowName;

    /** 业务 ID */
    private String businessId;

    /** 当前节点名称 */
    private String nodeName;

    /** 流程状态（warm-flow flow_status 码值） */
    private String flowStatus;

    /** 发起人（实例 create_by，用户 ID 字符串口径） */
    private String createBy;

    /** 实例创建时间 */
    private LocalDateTime instanceCreateTime;

    /** 流程变量（实例级，引擎 JSON 反序列化；无变量为空 Map） */
    private Map<String, Object> variables;

    /** 审批历史（时间正序，来自 flow_his_task） */
    private List<HistoryItem> history;

    /** 审批历史条目 */
    @Data
    public static class HistoryItem implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 节点名称 */
        private String nodeName;

        /** 审批人（用户 ID 字符串口径） */
        private String approver;

        /** 流转类型（PASS 通过 / REJECT 退回 / NONE 无动作） */
        private String skipType;

        /** 审批意见 */
        private String message;

        /** 记录创建时间 */
        private LocalDateTime createTime;
    }
}
