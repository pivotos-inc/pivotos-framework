package ${packageName}.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;
<#if hasSub?? && hasSub>
import java.util.List;
</#if>
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

<#if col.fkTable?? && col.fkTable?has_content>
    /** ${label}（关联显示，S50 / 2.4-F2） */
    private String ${col.javaField}Label;

</#if>
</#list>
<#if hasSub?? && hasSub>
    /** ${subFunctionName}明细（S51 / 2.4-F3；详情时填充） */
    private List<${subClassName}VO> items;
</#if>
}
