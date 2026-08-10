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

    /** 生成的文件列表（文件路径 → 文件内容） */
    private Map<String, String> generatedFiles;

    /** 创建人 */
    private Long createBy;

    /** 创建时间 */
    private LocalDateTime createTime;
}
