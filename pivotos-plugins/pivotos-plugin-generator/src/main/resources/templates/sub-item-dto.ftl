package ${packageName}.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
<#list subImportPaths as p>
import ${p};
</#list>

/**
 * ${subFunctionName} - 子项请求（S51 / 2.4-F3 主子表明细行；fk 由后端按主表 id 回写）
 *
 * @author ${author}
 * @date ${datetime}
 */
@Data
public class ${subClassName}ItemRequest {

<#list subInsertColumns as col>
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

</#list>
}
