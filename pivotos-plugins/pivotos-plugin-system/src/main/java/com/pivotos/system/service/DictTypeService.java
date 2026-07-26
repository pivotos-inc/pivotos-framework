package com.pivotos.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.DictTypeQuery;
import com.pivotos.system.domain.dto.DictTypeSaveRequest;
import com.pivotos.system.domain.entity.SysDictType;
import com.pivotos.system.domain.vo.DictTypeVO;

/** 字典类型服务 */
public interface DictTypeService extends IService<SysDictType> {

    /** 分页查询字典类型 */
    PageResult<DictTypeVO> pageTypes(DictTypeQuery query);

    /** 查询字典类型详情 */
    DictTypeVO getType(Long dictId);

    /** 新增字典类型，返回字典ID */
    Long createType(DictTypeSaveRequest request);

    /** 修改字典类型 */
    void updateType(DictTypeSaveRequest request);

    /** 删除字典类型（级联删除其字典数据） */
    void deleteType(Long dictId);
}
