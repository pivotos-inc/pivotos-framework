package com.pivotos.workflow.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 流程定义列表展示 VO。
 */
@Data
public class FlowDefinitionVO {

    private Long id;

    /** 流程编码 */
    private String flowCode;

    /** 流程名称 */
    private String flowName;

    /** 设计器模型（CLASSICS / MIMIC） */
    private String modelValue;

    /** 流程类别 */
    private String category;

    /** 流程版本 */
    private String version;

    /** 是否发布（0 未发布 / 1 已发布 / 9 失效） */
    private Integer isPublish;

    /** 流程激活状态（0 挂起 / 1 激活） */
    private Integer activityStatus;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 更新时间 */
    private LocalDateTime updateTime;
}
