package com.pivotos.system.facade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.system.api.dto.UserDTO;
import com.pivotos.system.api.facade.IUserFacade;
import com.pivotos.system.convert.UserConvert;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;

/** 用户门面本地实现（单体形态；微服务形态可整体替换为远程实现） */
@Component
@RequiredArgsConstructor
public class UserLocalFacade implements IUserFacade {

    private final UserService userService;
    private final UserConvert userConvert;

    @Override
    public UserDTO getById(Long userId) {
        return userConvert.toDto(userService.getById(userId));
    }

    @Override
    public UserDTO getByUsername(String username) {
        return userConvert.toDto(userService.getByUsername(username));
    }

    @Override
    public List<UserDTO> listByIds(Collection<Long> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return List.of();
        }
        return userConvert.toDtoList(userService.listByIds(userIds));
    }

    @Override
    public List<UserDTO> listActiveOptions(int limit, String keyword) {
        int size = Math.min(limit <= 0 ? 50 : limit, 100);
        return userConvert.toDtoList(userService.list(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getStatus, 0)
                        .and(keyword != null && !keyword.isBlank(), w -> w
                                .like(SysUser::getUsername, keyword)
                                .or()
                                .like(SysUser::getNickname, keyword))
                        .orderByAsc(SysUser::getId)
                        .last("LIMIT " + size)));
    }
}
