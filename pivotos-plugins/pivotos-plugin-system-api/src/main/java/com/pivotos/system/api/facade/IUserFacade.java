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

    /**
     * 活跃用户选项（移动端加签选人等场景，S81）
     *
     * @param limit   返回条数上限（实现侧封顶 100）
     * @param keyword 关键字（可空：模糊匹配用户名/昵称）
     * @return 状态正常的用户列表，按 ID 升序
     */
    List<UserDTO> listActiveOptions(int limit, String keyword);
}
