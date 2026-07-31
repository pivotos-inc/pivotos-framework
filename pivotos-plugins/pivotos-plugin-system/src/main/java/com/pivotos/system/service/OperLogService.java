package com.pivotos.system.service;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.OperLogQuery;
import com.pivotos.system.domain.entity.SysOperLog;
import com.pivotos.system.domain.vo.OperLogVO;

/** 操作日志服务 */
public interface OperLogService {

    /** 保存操作日志（OperLogAspect 切面调用） */
    void saveLog(SysOperLog entity);

    /** 分页查询 */
    PageResult<OperLogVO> pageLogs(OperLogQuery query);
}
