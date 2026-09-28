package com.pivotos.system.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 租户实体（SaaS 多租户管理面，共享库口径） */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_tenant")
public class SysTenant extends BaseDO {

    /** 租户编码 */
    private String tenantCode;

    /** 租户名称 */
    private String tenantName;

    /** 套餐 ID（NULL=平台代管，不做套餐过滤） */
    private Long packageId;

    /** 账号数上限（0=不限） */
    private Integer accountLimit;

    /** 过期时间（NULL=永不过期） */
    private LocalDateTime expireTime;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    private String remark;
}
