package com.pivotos.workflow.domain.dto;

import lombok.Data;

import java.util.List;

/**
 * 加签命令（S78 F2）：当前处理人为待办任务追加审批人。
 * <p>
 * 引擎语义为 warm-flow 原生 addSignature（或签：任一审批人通过即推进节点）。
 */
@Data
public class AddSignatureCmd {

    /** 待办任务 ID */
    private Long taskId;

    /** 被加签人用户 ID 集合（必填，字符串口径对齐 warm-flow handler） */
    private List<String> userIds;

    /** 加签说明（可选） */
    private String message;
}
