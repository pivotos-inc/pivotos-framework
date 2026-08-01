package com.pivotos.system.service;

import com.pivotos.system.domain.vo.OnlineUserVO;

import java.util.List;

/**
 * 在线用户服务接口（基于 Sa-Token 会话）。
 */
public interface OnlineUserService {

    /**
     * 查询当前 sys-user 类型的所有在线用户。
     *
     * @return 在线用户列表
     */
    List<OnlineUserVO> listOnlineUsers();

    /**
     * 强退指定 Token 对应的会话。
     *
     * @param tokenValue Sa-Token Token 明文值
     */
    void kickoutByToken(String tokenValue);
}
