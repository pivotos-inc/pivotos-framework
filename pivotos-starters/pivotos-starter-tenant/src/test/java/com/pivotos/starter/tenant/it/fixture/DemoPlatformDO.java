package com.pivotos.starter.tenant.it.fixture;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 测试夹具：平台共享表实体（t_platform，无 tenant_id 列，进 ignore-tables）
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_platform")
public class DemoPlatformDO extends BaseDO {

    private static final long serialVersionUID = 1L;

    private String name;
}
