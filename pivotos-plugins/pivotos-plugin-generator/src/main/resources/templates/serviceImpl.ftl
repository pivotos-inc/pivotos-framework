package ${packageName}.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import ${packageName}.domain.entity.${className};
import ${packageName}.domain.dto.${className}CreateRequest;
import ${packageName}.domain.dto.${className}UpdateRequest;
import ${packageName}.domain.dto.${className}QueryRequest;
import ${packageName}.domain.vo.${className}VO;
import ${packageName}.mapper.${className}Mapper;
import ${packageName}.service.${className}Service;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * ${functionName} - 服务实现
 *
 * @author ${author}
 * @date ${datetime}
 */
@Slf4j
@Service
public class ${className}ServiceImpl implements ${className}Service {

    @Resource
    private ${className}Mapper ${classVarName}Mapper;

    @Override
    public IPage<${className}VO> selectPage(IPage<${className}> page, ${className}QueryRequest query) {
        LambdaQueryWrapper<${className}> wq = Wrappers.lambdaQuery();
<#list queryColumns as col>
<#if col.queryType == "LIKE">
        wq.like(query.get${col.javaField?cap_first}() != null, ${className}::get${col.javaField?cap_first}, query.get${col.javaField?cap_first}());
<#elseif col.queryType == "BETWEEN">
        wq.ge(query.get${col.javaField?cap_first}Begin() != null, ${className}::get${col.javaField?cap_first}, query.get${col.javaField?cap_first}Begin())
          .le(query.get${col.javaField?cap_first}End() != null, ${className}::get${col.javaField?cap_first}, query.get${col.javaField?cap_first}End());
<#else>
        wq.eq(query.get${col.javaField?cap_first}() != null, ${className}::get${col.javaField?cap_first}, query.get${col.javaField?cap_first}());
</#if>
</#list>
        wq.orderByDesc(${className}::getCreateTime);

        IPage<${className}> resultPage = ${classVarName}Mapper.selectPage(page, wq);
        return resultPage.convert(entity -> {
            ${className}VO vo = new ${className}VO();
            BeanUtil.copyProperties(entity, vo);
            return vo;
        });
    }

    @Override
    public ${className}VO selectById(Long id) {
        ${className} entity = ${classVarName}Mapper.selectById(id);
        if (entity == null) {
            throw new RuntimeException("${tableComment}不存在");
        }
        ${className}VO vo = new ${className}VO();
        BeanUtil.copyProperties(entity, vo);
        return vo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void create(${className}CreateRequest request) {
        ${className} entity = new ${className}();
        BeanUtil.copyProperties(request, entity);
        entity.setId(null);
        ${classVarName}Mapper.insert(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(${className}UpdateRequest request) {
        ${className} entity = ${classVarName}Mapper.selectById(request.getId());
        if (entity == null) {
            throw new RuntimeException("${tableComment}不存在");
        }
        BeanUtil.copyProperties(request, entity);
        ${classVarName}Mapper.updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> ids) {
        ${classVarName}Mapper.deleteBatchIds(ids);
    }
}
