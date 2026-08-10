<#-- =====================================================
 uni-app API 文件模板
 输出: pivotos-app/src/api/{moduleName}/{businessName}.ts
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
import { del, get, post, put } from '@/utils/request';

/** ${functionName} VO */
export interface ${className}VO {
  id: number;
<#list listColumns as col>
  /** ${col.columnComment} */
  ${col.javaField}: ${tsType(col.javaType)};
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
export interface ${className}Query {
<#list queryColumns as col>
  /** ${col.columnComment} */
  ${col.javaField}?: ${tsType(col.javaType)};
</#list>
  pageNum: number;
  pageSize: number;
}

/** 分页查询 */
export function select${className}Page(query: ${className}Query) {
  return get<{ list: ${className}VO[]; total: number }>('/${moduleName}/${businessName}/page', query);
}

/** 查询详情 */
export function get${className}(id: number): Promise<${className}VO> {
  return get<${className}VO>('/${moduleName}/${businessName}/' + id);
}

/** 新增 */
export function create${className}(body: ${className}SaveRequest): Promise<unknown> {
  return post<unknown>('/${moduleName}/${businessName}', body);
}

/** 修改 */
export function update${className}(body: ${className}SaveRequest): Promise<unknown> {
  return put<unknown>('/${moduleName}/${businessName}', body);
}

/** 删除 */
export function delete${className}(id: number): Promise<unknown> {
  return del<unknown>('/${moduleName}/${businessName}/' + id);
}
