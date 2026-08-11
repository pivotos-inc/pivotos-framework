package ${packageName}.service;

import com.pivotos.common.core.page.PageResult;
import ${packageName}.domain.dto.${className}CreateRequest;
import ${packageName}.domain.dto.${className}UpdateRequest;
import ${packageName}.domain.dto.${className}QueryRequest;
import ${packageName}.domain.vo.${className}VO;

import java.util.List;
<#if hasFk>
import java.util.Map;
</#if>

/**
 * ${functionName} - 服务接口
 *
 * @author ${author}
 * @date ${datetime}
 */
public interface ${className}Service {

    /**
     * 分页查询${functionName}
     */
    PageResult<${className}VO> selectPage(${className}QueryRequest query);
<#if hasTree?? && hasTree>

    /**
     * 全量查询并组装树（S53 / 2.4-F4；千行内内存组装，PC tree-table 用）
     */
    List<${className}VO> selectTreeList(${className}QueryRequest query);
</#if>

    /**
     * 查询${functionName}详情
     */
    ${className}VO selectById(Long id);

    /**
     * 新增${functionName}
     */
    void create(${className}CreateRequest request);

    /**
     * 更新${functionName}
     */
    void update(${className}UpdateRequest request);

    /**
     * 删除${functionName}
     */
    void delete(List<Long> ids);
<#if hasFk>

    /**
     * 查询关联下拉选项（S50 / 2.4-F2；field = 实体字段名，返回 [{value, label}]）
     */
    List<Map<String, Object>> selectFkOptions(String field);
</#if>
}
