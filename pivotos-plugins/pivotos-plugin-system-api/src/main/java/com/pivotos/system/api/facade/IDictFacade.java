package com.pivotos.system.api.facade;

import com.pivotos.system.api.dto.DictDataDTO;

import java.util.List;

/**
 * 字典门面契约
 */
public interface IDictFacade {

    /**
     * 按字典类型查询字典数据（仅返回正常状态项，按 sort 升序）
     *
     * @param dictType 字典类型
     * @return 字典数据列表，无数据返回空列表
     */
    List<DictDataDTO> listDictData(String dictType);

    /**
     * 翻译字典键值为标签
     *
     * @param dictType  字典类型
     * @param dictValue 字典键值
     * @return 字典标签，未命中返回 null
     */
    String getDictLabel(String dictType, String dictValue);
}
