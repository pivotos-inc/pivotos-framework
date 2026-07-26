package com.pivotos.system.api.facade;

import com.pivotos.system.api.dto.UserDTO;

import java.util.Collection;
import java.util.List;

/**
 * 用户门面契约
 *
 * <p>跨 Plugin 读取用户信息的唯一入口（禁止跨 Plugin 直接依赖实现模块）。
 * 单体部署由 system 插件提供本地实现；微服务形态可替换为远程实现，消费方 0 改动。
 */
public interface IUserFacade {

    /**
     * 按 ID 查询用户
     *
     * @param userId 用户 ID
     * @return 用户 DTO，不存在返回 null
     */
    UserDTO getById(Long userId);

    /**
     * 按用户名查询用户
     *
     * @param username 用户名
     * @return 用户 DTO，不存在返回 null
     */
    UserDTO getByUsername(String username);

    /**
     * 按 ID 集合批量查询用户
     *
     * @param userIds 用户 ID 集合
     * @return 用户列表（不存在的 ID 自动忽略）
     */
    List<UserDTO> listByIds(Collection<Long> userIds);
}
