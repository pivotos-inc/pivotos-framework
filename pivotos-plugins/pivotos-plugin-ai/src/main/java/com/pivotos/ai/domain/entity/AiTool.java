package com.pivotos.ai.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 工具注册表实体（S98 A2）
 *
 * <p>@Tool 业务方法的元数据登记：启动同步器按 ToolCallbackProvider 汇聚结果 upsert，
 * 运行期由 GuardedToolCallback 做「停用检查 → 角色白名单 → 二次确认预检」三道闸。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("ai_tool")
public class AiTool extends TenantBaseDO {

    /** 工具名（@Tool name，全局唯一） */
    private String toolName;

    /** 展示名（缺省同工具名） */
    private String displayName;

    /** 工具描述（同步自 @Tool description） */
    private String description;

    /** 工具类型（read=只读 write=写操作，见 ToolType） */
    private String toolType;

    /** 写操作是否需二次确认（0否 1是） */
    private Integer confirmRequired;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 来源（register=@Tool 扫描自动注册） */
    private String source;
}
