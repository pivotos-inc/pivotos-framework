package com.pivotos.workflow.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 流程抄送记录实体（S78 工作流深化二期）。
 * <p>
 * warm-flow 引擎无抄送能力，本表为插件自建；flow_name / creator_name 为冗余字段
 * （禁跨前缀联表，sys_user 昵称不可 join），发起落库时写入。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("flow_cc")
public class FlowCc extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 流程实例 ID（flow_instance.id） */
    private Long instanceId;

    /** 抄送收件人用户 ID */
    private Long userId;

    /** 流程名称（冗余） */
    private String flowName;

    /** 发起人昵称（冗余） */
    private String creatorName;

    /** 已读标记：0 未读 1 已读 */
    private Integer readFlag;

    /** 阅读时间 */
    private LocalDateTime readTime;
}
