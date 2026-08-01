package ${packageName}.domain.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;
<#if hasImportableTypes>
<#list importTypes as t>
import java.math.BigDecimal;
<#if t == "BigDecimal">
</#if>
</#list>
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
</#if>

/**
 * ${functionName} - 实体
 *
 * @author ${author}
 * @date ${datetime}
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("${tableName}")
public class ${className} extends BaseDO {

<#if pkColumn??>
    @TableId
    private ${pkColumn.javaType} id;
</#if>

<#list columns as col>
<#if col.javaField != "id" && col.javaField != "createBy" && col.javaField != "createTime" && col.javaField != "updateBy" && col.javaField != "updateTime" && col.javaField != "deleted">
    /** ${col.columnComment!} */
    private ${col.javaType} ${col.javaField};

</#if>
</#list>
}
