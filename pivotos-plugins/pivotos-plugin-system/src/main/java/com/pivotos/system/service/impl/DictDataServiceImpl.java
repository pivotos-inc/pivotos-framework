package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.enums.CommonStatusEnum;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.convert.DictDataConvert;
import com.pivotos.system.domain.dto.DictDataQuery;
import com.pivotos.system.domain.dto.DictDataSaveRequest;
import com.pivotos.system.domain.entity.SysDictData;
import com.pivotos.system.domain.entity.SysDictType;
import com.pivotos.system.domain.vo.DictDataVO;
import com.pivotos.system.mapper.SysDictDataMapper;
import com.pivotos.system.mapper.SysDictTypeMapper;
import com.pivotos.system.service.DictDataService;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Objects;

/** 字典数据服务实现 */
@Service
@RequiredArgsConstructor
public class DictDataServiceImpl extends ServiceImpl<SysDictDataMapper, SysDictData> implements DictDataService {

    private final DictDataConvert dictDataConvert;
    private final SysDictTypeMapper dictTypeMapper;

    @Override
    public PageResult<DictDataVO> pageData(DictDataQuery query) {
        Page<SysDictData> page = page(PageUtils.toMpPage(query), Wrappers.<SysDictData>lambdaQuery()
                .eq(StringUtils.hasText(query.getDictType()), SysDictData::getDictType, query.getDictType())
                .like(StringUtils.hasText(query.getDictLabel()), SysDictData::getDictLabel, query.getDictLabel())
                .eq(query.getStatus() != null, SysDictData::getStatus, query.getStatus())
                .orderByAsc(SysDictData::getSort));
        return PageUtils.toPageResult(page, dictDataConvert.toVoList(page.getRecords()));
    }

    @Override
    public DictDataVO getData(Long dictCode) {
        return dictDataConvert.toVo(requireData(dictCode));
    }

    @Override
    public Long createData(DictDataSaveRequest request) {
        checkTypeExists(request.getDictType());
        SysDictData entity = dictDataConvert.toEntity(request);
        entity.setId(null);
        save(entity);
        return entity.getId();
    }

    @Override
    public void updateData(DictDataSaveRequest request) {
        requireData(request.getId());
        checkTypeExists(request.getDictType());
        updateById(dictDataConvert.toEntity(request));
    }

    @Override
    public void deleteData(Long dictCode) {
        requireData(dictCode);
        removeById(dictCode);
    }

    @Override
    public List<SysDictData> listEnabledByType(String dictType) {
        return list(Wrappers.<SysDictData>lambdaQuery()
                .eq(SysDictData::getDictType, dictType)
                .eq(SysDictData::getStatus, CommonStatusEnum.ENABLED.getValue())
                .orderByAsc(SysDictData::getSort));
    }

    private SysDictData requireData(Long dictCode) {
        SysDictData data = getById(dictCode);
        if (data == null) {
            throw new ServiceException(SystemErrorCode.DICT_DATA_NOT_FOUND);
        }
        return data;
    }

    /** 字典数据必须挂在已存在的字典类型下 */
    private void checkTypeExists(String dictType) {
        long count = dictTypeMapper.selectCount(Wrappers.<SysDictType>lambdaQuery()
                .eq(SysDictType::getDictType, dictType));
        if (count == 0) {
            throw new ServiceException(SystemErrorCode.DICT_TYPE_NOT_FOUND);
        }
    }
}
