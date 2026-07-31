package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 操作日志实体（@Log 切面采集，平台共享表） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_oper_log")
public class SysOperLog extends BaseDO {

    /** 功能模块 */
    private String module;

    /** 操作类型（新增/修改/删除/发布/撤回等） */
    private String operType;

    /** 操作人用户名 */
    private String operName;

    /** 操作人 ID */
    private Long operUserId;

    /** 目标方法（类.方法） */
    private String method;

    /** HTTP 方法 */
    private String requestMethod;

    /** 请求 URL */
    private String requestUrl;

    /** 入参摘要（脱敏 + 截断） */
    private String requestParams;

    /** 结果（0成功 1失败） */
    private Integer status;

    /** 异常信息（失败时） */
    private String errorMsg;

    /** 耗时（毫秒） */
    private Long duration;

    /** 操作时间 */
    private LocalDateTime operTime;
}
