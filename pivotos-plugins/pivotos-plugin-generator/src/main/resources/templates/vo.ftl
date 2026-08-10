package ${packageName}.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;
<#list importPaths as p>
import ${p};
</#list>

/**
 * ${functionName} - 视图对象
 *
 * @author ${author}
 * @date ${datetime}
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class ${className}VO extends BaseDTO {

<#list voColumns as col>
<#assign label = col.columnComment!''>
<#if label == ''><#assign label = col.javaField></#if>
    /** ${label} */
    private ${col.javaType} ${col.javaField};

</#list>
}
