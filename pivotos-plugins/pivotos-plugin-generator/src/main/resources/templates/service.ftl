package ${packageName}.service;

import com.pivotos.common.core.page.PageResult;
import ${packageName}.domain.dto.${className}CreateRequest;
import ${packageName}.domain.dto.${className}UpdateRequest;
import ${packageName}.domain.dto.${className}QueryRequest;
import ${packageName}.domain.vo.${className}VO;

import java.util.List;

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
}
