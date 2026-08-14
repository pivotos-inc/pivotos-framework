package com.pivotos.workflow.domain.dto;

import lombok.Data;

import java.util.Map;

/**
 * 发起流程实例命令。
 */
@Data
public class StartInstanceCmd {

    /** 流程编码（必填，对应 flow_definition.flow_code） */
    private String flowCode;

    /** 业务 ID（关联业务表主键） */
    private String businessId;

    /** 业务名称（展示用，如"张三的请假单"） */
    private String businessName;

    /** 流程变量（可传递给流程节点的条件参数） */
    private Map<String, Object> variable;
}
