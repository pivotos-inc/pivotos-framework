<#-- =====================================================
 PC 端 API 文件模板
 输出: pivotos-ui/apps/admin/src/api/{moduleName}/{businessName}.ts
===================================================== -->
<#function tsType javaType>
  <#if javaType == "String"><#return "string">
  <#elseif javaType == "Integer" || javaType == "Long" || javaType == "BigDecimal" || javaType == "Float" || javaType == "Double"><#return "number">
  <#elseif javaType == "Boolean"><#return "boolean">
  <#elseif javaType == "LocalDateTime" || javaType == "LocalDate" || javaType == "LocalTime"><#return "string">
  <#else><#return "any">
  </#if>
</#function>
/**
 * ${functionName} API
 * @author ${author}
 * @date ${datetime}
 */
import { request } from '../request';
import type { PageQuery } from '@pivotos/types';

/** ${functionName} VO */
export interface ${className}VO {
  id: number;
<#list listColumns as col>
  /** ${col.columnComment} */
  ${col.javaField}: ${tsType(col.javaType)};
<#if col.fkTable?? && col.fkTable?has_content>
  /** ${col.columnComment}（关联显示） */
  ${col.javaField}Label?: string;
</#if>
</#list>
  createTime?: string;
}

/** ${functionName} 保存请求 */
export interface ${className}SaveRequest {
  id?: number;
<#list insertColumns as col>
  /** ${col.columnComment} */
  ${col.javaField}?: ${tsType(col.javaType)};
</#list>
}

/** ${functionName} 查询参数 */
export interface ${className}Query extends PageQuery {
<#list queryColumns as col>
  /** ${col.columnComment} */
  ${col.javaField}?: ${tsType(col.javaType)};
</#list>
}

/** 查询${functionName}详情 */
export function get${className}(id: number): Promise<${className}VO> {
  return request.get<unknown, ${className}VO>('/${moduleName}/${businessName}/' + id);
}

/** 新增${functionName} */
export function create${className}(body: ${className}SaveRequest): Promise<void> {
  return request.post<unknown, void>('/${moduleName}/${businessName}', body);
}

/** 修改${functionName} */
export function update${className}(body: ${className}SaveRequest): Promise<void> {
  return request.put<unknown, void>('/${moduleName}/${businessName}', body);
}

/** 删除${functionName} */
export function delete${className}(ids: string): Promise<void> {
  return request.delete<unknown, void>('/${moduleName}/${businessName}/' + ids);
}
<#if hasFk>

/** fk 下拉选项 */
export interface ${className}FkOption {
  value: string | number;
  label: string;
}

/** 查询${functionName}关联下拉选项（S50 / 2.4-F2） */
export function get${className}FkOptions(field: string): Promise<${className}FkOption[]> {
  return request.get<unknown, ${className}FkOption[]>('/${moduleName}/${businessName}/fk-options/' + field);
}
</#if>
