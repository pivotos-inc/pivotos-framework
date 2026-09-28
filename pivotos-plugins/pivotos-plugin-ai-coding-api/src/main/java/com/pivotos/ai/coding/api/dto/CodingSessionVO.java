package com.pivotos.ai.coding.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * AI Coding 会话 VO
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodingSessionVO implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 会话 ID */
    private Long id;

    /** 用户自然语言描述 */
    private String description;

    /** 解析结果：模块名 */
    private String moduleName;

    /** 解析结果：表名 */
    private String tableName;

    /** 解析结果：功能名称 */
    private String functionName;

    /** 解析结果：业务名 */
    private String businessName;

    /** 状态：0=解析中 1=待评审 2=已应用 3=失败 */
    private Integer status;

    /** 任务类型：1=单表CRUD 2=Plugin骨架 */
    private Integer taskType;

    /** 任务类型特定参数（如骨架 pluginName/errorCodeBase/tablePrefix） */
    private Map<String, Object> extra;

    /** 生成的文件列表（文件路径 → 文件内容） */
    private Map<String, String> generatedFiles;

    /** A4-1 定位结果快照（taskType=5 修改型；见 LocateResultVO） */
    private Map<String, Object> locate;

    /** 结构化 edit 指令（taskType=5 修改型） */
    private Map<String, Object> edit;

    /** 确定性渲染的 unified diff（taskType=5 修改型；评审面展示物） */
    private String diff;

    /** 自动门禁结果（taskType=5 修改型） */
    private Map<String, Object> gate;

    /** 创建人 */
    private Long createBy;

    /** 创建时间 */
    private LocalDateTime createTime;
}
