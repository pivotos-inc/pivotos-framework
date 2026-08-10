package ${packageName}.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
<#list importPaths as p>
import ${p};
</#list>

/**
 * ${functionName} - 新增请求
 *
 * @author ${author}
 * @date ${datetime}
 */
@Data
public class ${className}CreateRequest {

<#list insertColumns as col>
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
}
