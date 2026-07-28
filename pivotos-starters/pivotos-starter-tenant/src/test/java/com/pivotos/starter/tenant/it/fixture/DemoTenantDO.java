package com.pivotos.starter.tenant.it.fixture;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 测试夹具：租户业务表实体（t_tenant_demo，含 tenant_id 列）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_tenant_demo")
public class DemoTenantDO extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    private String title;
}
