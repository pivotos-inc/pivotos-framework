package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.convert.DictTypeConvert;
import com.pivotos.system.domain.dto.DictTypeQuery;
import com.pivotos.system.domain.dto.DictTypeSaveRequest;
import com.pivotos.system.domain.entity.SysDictData;
import com.pivotos.system.domain.entity.SysDictType;
import com.pivotos.system.domain.vo.DictTypeVO;
import com.pivotos.system.mapper.SysDictDataMapper;
import com.pivotos.system.mapper.SysDictTypeMapper;
import com.pivotos.system.service.DictTypeService;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** 字典类型服务实现 */
@Service
@RequiredArgsConstructor
public class DictTypeServiceImpl extends ServiceImpl<SysDictTypeMapper, SysDictType> implements DictTypeService {

    private final DictTypeConvert dictTypeConvert;
    private final SysDictDataMapper dictDataMapper;

    @Override
    public PageResult<DictTypeVO> pageTypes(DictTypeQuery query) {
        Page<SysDictType> page = page(PageUtils.toMpPage(query), Wrappers.<SysDictType>lambdaQuery()
                .like(StringUtils.hasText(query.getDictName()), SysDictType::getDictName, query.getDictName())
                .like(StringUtils.hasText(query.getDictType()), SysDictType::getDictType, query.getDictType())
                .eq(query.getStatus() != null, SysDictType::getStatus, query.getStatus())
                .orderByAsc(SysDictType::getId));
        return PageUtils.toPageResult(page, dictTypeConvert.toVoList(page.getRecords()));
    }

    @Override
    public DictTypeVO getType(Long dictId) {
        return dictTypeConvert.toVo(requireType(dictId));
    }

    @Override
    public Long createType(DictTypeSaveRequest request) {
        checkTypeUnique(request.getDictType(), null);
        SysDictType entity = dictTypeConvert.toEntity(request);
        entity.setId(null);
        save(entity);
        return entity.getId();
    }

    @Override
    public void updateType(DictTypeSaveRequest request) {
        SysDictType exist = requireType(request.getId());
        checkTypeUnique(request.getDictType(), request.getId());
        updateById(dictTypeConvert.toEntity(request));
        // 字典类型变更时同步字典数据的 dict_type 字段
        if (!exist.getDictType().equals(request.getDictType())) {
            SysDictData update = new SysDictData();
            update.setDictType(request.getDictType());
            dictDataMapper.update(update, Wrappers.<SysDictData>lambdaQuery()
                    .eq(SysDictData::getDictType, exist.getDictType()));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteType(Long dictId) {
        SysDictType type = requireType(dictId);
        removeById(dictId);
        dictDataMapper.delete(Wrappers.<SysDictData>lambdaQuery()
                .eq(SysDictData::getDictType, type.getDictType()));
    }

    private SysDictType requireType(Long dictId) {
        SysDictType type = getById(dictId);
        if (type == null) {
            throw new ServiceException(SystemErrorCode.DICT_TYPE_NOT_FOUND);
        }
        return type;
    }

    private void checkTypeUnique(String dictType, Long excludeId) {
        long count = count(Wrappers.<SysDictType>lambdaQuery()
                .eq(SysDictType::getDictType, dictType)
                .ne(excludeId != null, SysDictType::getId, excludeId));
        if (count > 0) {
            throw new ServiceException(SystemErrorCode.DICT_TYPE_EXISTS);
        }
    }
}
