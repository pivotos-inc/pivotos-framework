package ${packageName}.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
<#if hasSub>
import jakarta.validation.Valid;
import java.util.List;
</#if>
<#list importPaths as p>
import ${p};
</#list>

/**
 * ${functionName} - 更新请求
 *
 * @author ${author}
 * @date ${datetime}
 */
@Data
public class ${className}UpdateRequest {

    /** 主键 */
    @NotNull(message = "id 不能为空")
    private <#if pkColumn??>${pkColumn.javaType}<#else>Long</#if> id;

<#list editColumns as col>
<#if col.javaField != "id" && col.javaField != "createBy" && col.javaField != "createTime" && col.javaField != "updateBy" && col.javaField != "updateTime" && col.javaField != "deleted">
<#assign label = col.columnComment!''>
<#if label == ''><#assign label = col.javaField></#if>
    /** ${label} */
<#if col.isRequired == 1>
<#if col.javaType == "String">
    @NotBlank(message = "${label}不能为空")
<#else>
    @NotNull(message = "${label}不能为空")
</#if>
</#if>
    private ${col.javaType} ${col.javaField};

</#if>
</#list>
<#if hasSub>
    /** ${subFunctionName}明细（S51 / 2.4-F3；全量替换语义：提交即覆盖旧明细） */
    @Valid
    private List<${subClassName}ItemRequest> items;
</#if>
}
