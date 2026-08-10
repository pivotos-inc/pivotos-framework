package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.convert.OperLogConvert;
import com.pivotos.system.domain.dto.OperLogQuery;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.domain.vo.OperLogVO;
import com.pivotos.system.mapper.SysOperLogMapper;
import com.pivotos.system.service.OperLogService;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 操作日志实现 */
@Service
@RequiredArgsConstructor
public class OperLogServiceImpl extends ServiceImpl<SysOperLogMapper, SysOperLog>
        implements OperLogService {

    private final OperLogConvert operLogConvert;

    @Override
    public void saveLog(SysOperLog entity) {
        save(entity);
    }

    @Override
    public PageResult<OperLogVO> pageLogs(OperLogQuery query) {
        Page<SysOperLog> page = page(PageUtils.toMpPage(query), Wrappers.<SysOperLog>lambdaQuery()
                .like(StringUtils.hasText(query.getModule()), SysOperLog::getModule, query.getModule())
                .eq(StringUtils.hasText(query.getOperType()), SysOperLog::getOperType, query.getOperType())
                .like(StringUtils.hasText(query.getOperName()), SysOperLog::getOperName, query.getOperName())
                .eq(query.getStatus() != null, SysOperLog::getStatus, query.getStatus())
                .ge(query.getBeginTime() != null, SysOperLog::getOperTime, query.getBeginTime())
                .le(query.getEndTime() != null, SysOperLog::getOperTime, query.getEndTime())
                .orderByDesc(SysOperLog::getOperTime));
        return PageUtils.toPageResult(page, operLogConvert.toVoList(page.getRecords()));
    }
}
