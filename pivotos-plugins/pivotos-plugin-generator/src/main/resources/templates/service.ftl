package ${packageName}.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import ${packageName}.domain.entity.${className};
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
    IPage<${className}VO> selectPage(IPage<${className}> page, ${className}QueryRequest query);

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
