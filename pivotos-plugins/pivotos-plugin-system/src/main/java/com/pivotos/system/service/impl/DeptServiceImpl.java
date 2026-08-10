package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.constant.CommonConstants;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.convert.DeptConvert;
import com.pivotos.system.domain.dto.DeptQuery;
import com.pivotos.system.domain.dto.DeptSaveRequest;
import com.pivotos.system.domain.entity.SysDept;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.vo.DeptVO;
import com.pivotos.system.mapper.SysDeptMapper;
import com.pivotos.system.mapper.SysUserMapper;
import com.pivotos.system.service.DeptService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 部门服务实现 */
@Service
@RequiredArgsConstructor
public class DeptServiceImpl extends ServiceImpl<SysDeptMapper, SysDept> implements DeptService {

    private final DeptConvert deptConvert;
    private final SysUserMapper userMapper;

    @Override
    public List<DeptVO> treeDepts(DeptQuery query) {
        List<SysDept> depts = list(Wrappers.<SysDept>lambdaQuery()
                .like(StringUtils.hasText(query.getDeptName()), SysDept::getDeptName, query.getDeptName())
                .eq(query.getStatus() != null, SysDept::getStatus, query.getStatus())
                .orderByAsc(SysDept::getParentId)
                .orderByAsc(SysDept::getSort));
        return buildDeptTree(deptConvert.toVoList(depts));
    }

    @Override
    public DeptVO getDept(Long deptId) {
        return deptConvert.toVo(requireDept(deptId));
    }

    @Override
    public Long createDept(DeptSaveRequest request) {
        SysDept entity = deptConvert.toEntity(request);
        entity.setId(null);
        entity.setAncestors(computeAncestors(request.getParentId()));
        save(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateDept(DeptSaveRequest request) {
        SysDept exist = requireDept(request.getId());
        if (Objects.equals(request.getId(), request.getParentId())) {
            throw new ServiceException(SystemErrorCode.DEPT_PARENT_INVALID);
        }
        SysDept entity = deptConvert.toEntity(request);
        // 父级变更：校验合法性并级联重算子孙 ancestors
        if (!Objects.equals(exist.getParentId(), request.getParentId())) {
            List<SysDept> all = list();
            Map<Long, List<SysDept>> childrenMap = buildChildrenMap(all);
            Set<Long> subtreeIds = collectSubtreeIds(request.getId(), childrenMap);
            if (subtreeIds.contains(request.getParentId())) {
                throw new ServiceException(SystemErrorCode.DEPT_PARENT_INVALID);
            }
            entity.setAncestors(computeAncestors(request.getParentId()));
            updateById(entity);
            cascadeUpdateChildrenAncestors(request.getId(), entity.getAncestors(), childrenMap);
        } else {
            updateById(entity);
        }
    }

    @Override
    public void deleteDept(Long deptId) {
        requireDept(deptId);
        long children = count(Wrappers.<SysDept>lambdaQuery().eq(SysDept::getParentId, deptId));
        if (children > 0) {
            throw new ServiceException(SystemErrorCode.DEPT_HAS_CHILDREN);
        }
        long users = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getDeptId, deptId));
        if (users > 0) {
            throw new ServiceException(SystemErrorCode.DEPT_HAS_USERS);
        }
        removeById(deptId);
    }

    private SysDept requireDept(Long deptId) {
        SysDept dept = getById(deptId);
        if (dept == null) {
            throw new ServiceException(SystemErrorCode.DEPT_NOT_FOUND);
        }
        return dept;
    }

    /** 计算新节点的 ancestors：根为 "0"，否则为 父ancestors + "," + 父ID */
    private String computeAncestors(Long parentId) {
        if (CommonConstants.TREE_ROOT_ID.equals(parentId)) {
            return "0";
        }
        SysDept parent = getById(parentId);
        if (parent == null) {
            throw new ServiceException(SystemErrorCode.DEPT_NOT_FOUND.getCode(), "父部门不存在");
        }
        if (!Objects.equals(CommonStatusEnum.ENABLED.getValue(), parent.getStatus())) {
            throw new ServiceException(SystemErrorCode.DEPT_NOT_FOUND.getCode(), "父部门已停用，不允许新增子部门");
        }
        return parent.getAncestors() + "," + parentId;
    }

    private Map<Long, List<SysDept>> buildChildrenMap(List<SysDept> all) {
        Map<Long, List<SysDept>> map = new HashMap<>();
        for (SysDept dept : all) {
            map.computeIfAbsent(dept.getParentId(), k -> new ArrayList<>()).add(dept);
        }
        return map;
    }

    /** 收集子孙ID集合（不含自身） */
    private Set<Long> collectSubtreeIds(Long deptId, Map<Long, List<SysDept>> childrenMap) {
        Set<Long> ids = new HashSet<>();
        collectSubtreeIds(deptId, childrenMap, ids);
        return ids;
    }

    private void collectSubtreeIds(Long deptId, Map<Long, List<SysDept>> childrenMap, Set<Long> ids) {
        for (SysDept child : childrenMap.getOrDefault(deptId, List.of())) {
            ids.add(child.getId());
            collectSubtreeIds(child.getId(), childrenMap, ids);
        }
    }

    /** 级联重算子孙 ancestors（内存中沿父子链推导，仅更新 ancestors 字段） */
    private void cascadeUpdateChildrenAncestors(Long deptId, String deptAncestors,
                                                Map<Long, List<SysDept>> childrenMap) {
        for (SysDept child : childrenMap.getOrDefault(deptId, List.of())) {
            String childAncestors = deptAncestors + "," + deptId;
            SysDept update = new SysDept();
            update.setId(child.getId());
            update.setAncestors(childAncestors);
            updateById(update);
            cascadeUpdateChildrenAncestors(child.getId(), childAncestors, childrenMap);
        }
    }

    @Override
    public Set<Long> getSubtreeDeptIds(Long deptId) {
        List<SysDept> all = list();
        Map<Long, List<SysDept>> childrenMap = buildChildrenMap(all);
        return collectSubtreeIds(deptId, childrenMap);
    }

    /** 平铺部门 VO → 树 */
    private List<DeptVO> buildDeptTree(List<DeptVO> flat) {
        Map<Long, DeptVO> byId = flat.stream()
                .collect(Collectors.toMap(DeptVO::getId, Function.identity()));
        List<DeptVO> roots = new ArrayList<>();
        for (DeptVO vo : flat) {
            DeptVO parent = byId.get(vo.getParentId());
            if (parent == null) {
                roots.add(vo);
            } else {
                if (parent.getChildren() == null) {
                    parent.setChildren(new ArrayList<>());
                }
                parent.getChildren().add(vo);
            }
        }
        return roots;
    }
}
