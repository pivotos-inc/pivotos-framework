package ${packageName}.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
<#list importPaths as p>
import ${p};
</#list>

/**
 * ${functionName} - 分页查询
 *
 * @author ${author}
 * @date ${datetime}
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ${className}QueryRequest extends PageQuery {

<#list queryColumns as col>
<#assign label = col.columnComment!''>
<#if label == ''><#assign label = col.javaField></#if>
<#if col.queryType == "BETWEEN">
    /** ${label}（起） */
    private ${col.javaType} ${col.javaField}Begin;

    /** ${label}（止） */
    private ${col.javaType} ${col.javaField}End;

<#else>
    /** ${label} */
    private ${col.javaType} ${col.javaField};

</#if>
</#list>
}
