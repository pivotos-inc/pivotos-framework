package com.pivotos.ai.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** AI 编排记录分页查询入参（A5-1 / S116） */
@Data
@EqualsAndHashCode(callSuper = true)
public class AiToolPlanQuery extends PageQuery {

    /** 意图（模糊） */
    private String intent;

    /** 计划状态（draft / success / need_confirm / failed） */
    private String status;
}
