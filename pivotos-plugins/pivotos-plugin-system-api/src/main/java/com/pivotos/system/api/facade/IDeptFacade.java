package com.pivotos.system.api.facade;

import com.pivotos.system.api.dto.DeptDTO;

import java.util.Collection;
import java.util.List;

/**
 * 部门门面契约
 */
public interface IDeptFacade {

    /**
     * 按 ID 查询部门
     *
     * @param deptId 部门 ID
     * @return 部门 DTO，不存在返回 null
     */
    DeptDTO getById(Long deptId);

    /**
     * 按 ID 集合批量查询部门
     *
     * @param deptIds 部门 ID 集合
     * @return 部门列表（不存在的 ID 自动忽略）
     */
    List<DeptDTO> listByIds(Collection<Long> deptIds);
}
