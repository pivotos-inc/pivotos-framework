package com.pivotos.migration.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 迁移 IR 节点表。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("migration_ir_node")
public class MigrationIrNode extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 所属任务 */
    private Long taskId;

    /** 节点类型：PROJECT/MODULE/TABLE/ENTITY/ROUTE/SERVICE/PAGE/COMPONENT */
    private String nodeType;

    /** 节点唯一标识（IR 内） */
    private String nodeId;

    /** 父节点标识 */
    private String parentNodeId;

    /** 关联模块 ID */
    private String moduleId;

    /** 节点名称 */
    private String name;

    /** 源文件相对路径 */
    private String sourcePath;

    /** 节点载荷（IR 属性 JSON） */
    private String payload;

    /** 节点关系（依赖/引用 JSON 数组） */
    private String relations;
}
