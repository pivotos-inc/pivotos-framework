package com.pivotos.system.facade;

import com.pivotos.system.api.dto.DictDataDTO;
import com.pivotos.system.api.facade.IDictFacade;
import com.pivotos.system.convert.DictDataConvert;
import com.pivotos.system.domain.entity.SysDictData;
import com.pivotos.system.service.DictDataService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 字典门面本地实现 */
@Component
@RequiredArgsConstructor
public class DictLocalFacade implements IDictFacade {

    private final DictDataService dictDataService;
    private final DictDataConvert dictDataConvert;

    @Override
    public List<DictDataDTO> listDictData(String dictType) {
        return dictDataConvert.toDtoList(dictDataService.listEnabledByType(dictType));
    }

    @Override
    public String getDictLabel(String dictType, String dictValue) {
        return dictDataService.listEnabledByType(dictType).stream()
                .filter(data -> Objects.equals(data.getDictValue(), dictValue))
                .map(SysDictData::getDictLabel)
                .findFirst()
                .orElse(null);
    }
}
