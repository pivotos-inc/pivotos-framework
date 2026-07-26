package com.pivotos.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.system.domain.dto.DeptQuery;
import com.pivotos.system.domain.dto.DeptSaveRequest;
import com.pivotos.system.domain.entity.SysDept;
import com.pivotos.system.domain.vo.DeptVO;

import java.util.List;

/** 部门服务 */
public interface DeptService extends IService<SysDept> {

    /** 部门树查询 */
    List<DeptVO> treeDepts(DeptQuery query);

    /** 查询部门详情 */
    DeptVO getDept(Long deptId);

    /** 新增部门（维护 ancestors），返回部门ID */
    Long createDept(DeptSaveRequest request);

    /** 修改部门（父级变更时级联重算子孙 ancestors） */
    void updateDept(DeptSaveRequest request);

    /** 删除部门（有子部门或部门下有用户则拒绝） */
    void deleteDept(Long deptId);
}
