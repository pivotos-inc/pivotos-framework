package com.pivotos.starter.mybatis.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 租户实体基类：多租户表继承本类。
 * 租户字段由审计填充写入，行级过滤由租户插件按 TenantContext 自动追加。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class TenantBaseDO extends BaseDO {

    private static final long serialVersionUID = 1L;

    /** 租户 ID */
    @TableField(fill = FieldFill.INSERT)
    private Long tenantId;
}
