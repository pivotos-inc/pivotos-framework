package com.pivotos.system.service;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.LoginLogQuery;
import com.pivotos.system.domain.vo.LoginLogVO;

/** 登录日志服务 */
public interface LoginLogService {

    /**
     * 记录登录日志（认证链路埋点，成功/失败都记；
     * 记录失败只告警，不影响登录主流程）
     *
     * @param username 登录账号
     * @param success  是否成功
     * @param msg      提示消息（失败原因等）
     */
    void record(String username, boolean success, String msg);

    /** 分页查询 */
    PageResult<LoginLogVO> pageLogs(LoginLogQuery query);
}
