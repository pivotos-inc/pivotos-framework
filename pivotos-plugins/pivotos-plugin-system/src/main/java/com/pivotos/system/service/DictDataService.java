package com.pivotos.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.DictDataQuery;
import com.pivotos.system.domain.dto.DictDataSaveRequest;
import com.pivotos.system.domain.entity.SysDictData;
import com.pivotos.system.domain.vo.DictDataVO;

import java.util.List;

/** 字典数据服务 */
public interface DictDataService extends IService<SysDictData> {

    /** 分页查询字典数据 */
    PageResult<DictDataVO> pageData(DictDataQuery query);

    /** 查询字典数据详情 */
    DictDataVO getData(Long dictCode);

    /** 新增字典数据，返回字典数据ID */
    Long createData(DictDataSaveRequest request);

    /** 修改字典数据 */
    void updateData(DictDataSaveRequest request);

    /** 删除字典数据 */
    void deleteData(Long dictCode);

    /** 按类型查询正常状态字典数据（按 sort 升序，门面/前端字典翻译用） */
    List<SysDictData> listEnabledByType(String dictType);
}
