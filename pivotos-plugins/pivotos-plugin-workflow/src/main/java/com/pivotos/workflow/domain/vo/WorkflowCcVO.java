package com.pivotos.workflow.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抄送记录视图（S78 F1）。
 * <p>
 * flowName / creatorName 为落库冗余字段；flowStatus / nodeName 查询时从
 * flow_instance 实时补齐（同前缀表内关联补齐，非 SQL 联表）。
 */
@Data
public class WorkflowCcVO {

    /** 抄送记录 ID */
    private Long id;

    /** 流程实例 ID */
    private Long instanceId;

    /** 流程名称 */
    private String flowName;

    /** 发起人昵称 */
    private String creatorName;

    /** 实例当前状态（实时补齐，warm-flow flowStatus 口径） */
    private String flowStatus;

    /** 实例当前节点（实时补齐） */
    private String nodeName;

    /** 已读标记：0 未读 1 已读 */
    private Integer readFlag;

    /** 阅读时间 */
    private LocalDateTime readTime;

    /** 抄送时间 */
    private LocalDateTime createTime;
}
