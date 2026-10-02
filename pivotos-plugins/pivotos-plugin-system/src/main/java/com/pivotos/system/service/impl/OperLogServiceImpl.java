package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.convert.OperLogConvert;
import com.pivotos.system.domain.dto.OperLogQuery;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.domain.vo.OperLogVO;
import com.pivotos.starter.search.api.template.SearchPage;
import com.pivotos.system.mapper.SysOperLogMapper;
import com.pivotos.system.search.OperLogSearchSupport;
import com.pivotos.system.service.OperLogService;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 操作日志实现。
 *
 * <p>S122：查询默认走 ST-SEARCH 抽象（{@code SearchTemplate}），由 {@code pivotos.search.type}
 * 决定落 simple 内存实现还是 ES 实现；检索不可用（未开启 / Starter 缺失 / 检索异常）时
 * 自动回退到 MyBatis-Plus 查询，行为与 S121 一致。
 */
@Service
@RequiredArgsConstructor
public class OperLogServiceImpl extends ServiceImpl<SysOperLogMapper, SysOperLog>
        implements OperLogService {

    private final OperLogConvert operLogConvert;
    private final OperLogSearchSupport searchSupport;

    @Override
    public void saveLog(SysOperLog entity) {
        save(entity);
        // 双写索引：simple 内存实现与 ES 实现都靠这条拿到数据；失败不影响落库
        searchSupport.index(entity);
    }

    @Override
    public PageResult<OperLogVO> pageLogs(OperLogQuery query) {
        SearchPage<SysOperLog> searched = searchSupport.search(query);
        if (searched != null) {
            return new PageResult<>(operLogConvert.toVoList(searched.getRecords()),
                    searched.getTotal(), searched.getPageNum(), searched.getPageSize());
        }
        return pageFromDb(query);
    }

    /** 原 MyBatis-Plus 查询路径：检索不可用时的兜底（也是 enabled=false 的回滚路径） */
    private PageResult<OperLogVO> pageFromDb(OperLogQuery query) {
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
