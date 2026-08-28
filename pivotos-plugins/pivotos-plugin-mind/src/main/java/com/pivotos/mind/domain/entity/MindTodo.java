package com.pivotos.mind.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 枢磐·智域待办事项
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mind_todo")
public class MindTodo extends BaseDO {

    /** 所属用户ID */
    private Long userId;

    /** 待办标题 */
    private String title;

    /** 备注 */
    private String remark;

    /** 优先级：low 低 / medium 中 / high 高 */
    private String priority;

    /** 状态：0 未完成 / 1 已完成 */
    private Integer status;

    /** 计划完成时间 */
    private LocalDateTime dueTime;

    /** 实际完成时间 */
    private LocalDateTime finishTime;
}
