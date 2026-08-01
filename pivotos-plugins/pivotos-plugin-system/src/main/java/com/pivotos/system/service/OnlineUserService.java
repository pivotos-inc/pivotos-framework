package com.pivotos.system.service;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.OnlineUserQuery;
import com.pivotos.system.domain.vo.OnlineUserVO;

/**
 * 在线用户服务接口（基于 Sa-Token 会话）。
 */
public interface OnlineUserService {

    /**
     * 分页查询当前 sys-user 类型的在线用户，按登录时间倒序。
     *
     * @param query 分页查询参数
     * @return 分页结果
     */
    PageResult<OnlineUserVO> pageOnlineUsers(OnlineUserQuery query);

    /**
     * 强退指定 Token 对应的会话。
     *
     * @param tokenValue Sa-Token Token 明文值
     */
    void kickoutByToken(String tokenValue);

    /**
     * 清空所有 sys-user 在线用户（强退除当前用户外的所有会话）。
     *
     * @return 强退的用户数量
     */
    int clearAllUsers();
}
