package ${packageName}.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.page.PageResult;
import ${packageName}.domain.entity.${className};
import ${packageName}.domain.dto.${className}CreateRequest;
import ${packageName}.domain.dto.${className}UpdateRequest;
import ${packageName}.domain.dto.${className}QueryRequest;
import ${packageName}.domain.vo.${className}VO;
import ${packageName}.mapper.${className}Mapper;
import ${packageName}.service.${className}Service;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
<#if hasFk>
import org.springframework.jdbc.core.JdbcTemplate;
</#if>
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

<#if hasFk>
import java.util.ArrayList;
import java.util.HashMap;
</#if>
import java.util.List;
<#if hasFk>
import java.util.Map;
import java.util.Objects;
</#if>

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
<#if hasFk>

    /** fk 显示值翻译 / 下拉选项查询（S50 / 2.4-F2；架构测试未禁 JdbcTemplate，A5 仅禁 mapper XML） */
    @Resource
    private JdbcTemplate jdbcTemplate;
</#if>

    @Override
    public PageResult<${className}VO> selectPage(${className}QueryRequest query) {
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

        Page<${className}> page = ${classVarName}Mapper.selectPage(
                new Page<>(query.getPageNum(), query.getPageSize()), wq);
        List<${className}VO> voList = page.getRecords().stream().map(entity -> {
            ${className}VO vo = new ${className}VO();
            BeanUtil.copyProperties(entity, vo);
            return vo;
        }).toList();
<#if hasFk>
        fillFkLabels(voList);
</#if>
        return new PageResult<>(voList, page.getTotal(), query.getPageNum(), query.getPageSize());
    }

    @Override
    public ${className}VO selectById(Long id) {
        ${className} entity = ${classVarName}Mapper.selectById(id);
        if (entity == null) {
            throw new RuntimeException("${tableComment}不存在");
        }
        ${className}VO vo = new ${className}VO();
        BeanUtil.copyProperties(entity, vo);
<#if hasFk>
        fillFkLabels(List.of(vo));
</#if>
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
<#if hasFk>

    // ==================== fk 关联下拉（S50 / 2.4-F2） ====================

    /**
     * fk 字段配置：字段名 → [关联表, 值列, 显示列, 逻辑删除过滤片段]。
     * 生成期固化常量；标识符在生成器配置保存时已过小写白名单校验。
     */
    private static final Map<String, String[]> FK_CONFIG = Map.ofEntries(
<#list fkColumns as col>
            Map.entry("${col.javaField}", new String[]{"${col.fkTable}", "${col.fkValueColumn}", "${col.fkLabelColumn}", "<#if fkDeletedFields?seq_contains(col.javaField)> AND `deleted` = 0</#if>"})<#sep>,
</#list>
    );

    @Override
    public List<Map<String, Object>> selectFkOptions(String field) {
        String[] cfg = FK_CONFIG.get(field);
        if (cfg == null) {
            throw new IllegalArgumentException("非关联字段: " + field);
        }
        return jdbcTemplate.queryForList(
                "SELECT `" + cfg[1] + "` AS `value`, `" + cfg[2] + "` AS `label` FROM `" + cfg[0] + "` WHERE 1 = 1" + cfg[3] + " LIMIT 500");
    }

    /** 批量回填 fk 显示值（每 fk 字段一次 IN 查询，避免 N+1） */
    private void fillFkLabels(List<${className}VO> vos) {
        if (vos == null || vos.isEmpty()) {
            return;
        }
<#list fkColumns as col>
        fill${col.javaField?cap_first}Label(vos);
</#list>
    }
<#list fkColumns as col>

    /** 回填「${col.columnComment}」显示值 */
    private void fill${col.javaField?cap_first}Label(List<${className}VO> vos) {
        List<Object> values = new ArrayList<>(vos.stream().map(${className}VO::get${col.javaField?cap_first})
                .filter(Objects::nonNull).distinct().toList());
        if (values.isEmpty()) {
            return;
        }
        String[] cfg = FK_CONFIG.get("${col.javaField}");
        String placeholders = String.join(", ", java.util.Collections.nCopies(values.size(), "?"));
        Map<String, String> labelMap = new HashMap<>();
        jdbcTemplate.query(
                "SELECT `" + cfg[1] + "`, `" + cfg[2] + "` FROM `" + cfg[0] + "` WHERE `" + cfg[1] + "` IN (" + placeholders + ")" + cfg[3],
                // 显式强转 RowCallbackHandler：lambda 同时匹配 ResultSetExtractor 重载会编译歧义（S50 实测）
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        labelMap.put(String.valueOf(rs.getObject(1)), rs.getString(2)),
                values.toArray());
        for (${className}VO vo : vos) {
            if (vo.get${col.javaField?cap_first}() != null) {
                vo.set${col.javaField?cap_first}Label(labelMap.get(String.valueOf(vo.get${col.javaField?cap_first}())));
            }
        }
    }
</#list>
</#if>
}
