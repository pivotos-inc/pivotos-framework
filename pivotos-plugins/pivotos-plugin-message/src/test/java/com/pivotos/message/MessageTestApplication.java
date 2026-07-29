package com.pivotos.message;

import com.pivotos.common.api.context.LoginUser;
import com.pivotos.starter.auth.support.AuthPermissionProvider;
import com.pivotos.system.api.dto.UserDTO;
import com.pivotos.system.api.facade.IUserFacade;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * message 插件集成测试启动类
 *
 * <p>只扫描 com.pivotos.message——插件可独立装配运行的证明。
 * 跨插件契约 IUserFacade / 底座 SPI AuthPermissionProvider 由测试替身提供，
 * 不引入 system 实现（红线：跨 Plugin 禁直接依赖，测试亦然）。
 */
@SpringBootApplication(scanBasePackages = "com.pivotos.message")
public class MessageTestApplication {

    /** 测试用户池：1 admin / 2 zhangsan / 3 lisi */
    static final Map<Long, UserDTO> USERS = Map.of(
            1L, user(1L, "admin", "13800000001", "admin@pivotos.local"),
            2L, user(2L, "zhangsan", "13800000002", "zhangsan@pivotos.local"),
            3L, user(3L, "lisi", "13800000003", "lisi@pivotos.local"));

    public static void main(String[] args) {
        SpringApplication.run(MessageTestApplication.class, args);
    }

    private static UserDTO user(Long id, String username, String mobile, String email) {
        UserDTO dto = new UserDTO();
        dto.setId(id);
        dto.setUsername(username);
        dto.setNickname(username);
        dto.setMobile(mobile);
        dto.setEmail(email);
        dto.setStatus(0);
        return dto;
    }

    /** IUserFacade 测试替身：按用户池过滤 */
    @Bean
    IUserFacade userFacadeStub() {
        return new IUserFacade() {
            @Override
            public UserDTO getById(Long userId) {
                return USERS.get(userId);
            }

            @Override
            public UserDTO getByUsername(String username) {
                return USERS.values().stream()
                        .filter(u -> u.getUsername().equals(username)).findFirst().orElse(null);
            }

            @Override
            public List<UserDTO> listByIds(Collection<Long> userIds) {
                return userIds.stream().filter(USERS::containsKey).map(USERS::get).toList();
            }
        };
    }

    /** 权限 SPI 测试替身：通配权限（等价 super_admin） */
    @Bean
    AuthPermissionProvider authPermissionProviderStub() {
        return new AuthPermissionProvider() {
            @Override
            public List<String> getPermissions(Object loginId, String loginType) {
                return List.of("*:*:*");
            }

            @Override
            public List<String> getRoles(Object loginId, String loginType) {
                return List.of("super_admin");
            }
        };
    }

    /** 便捷登录：createLoginSession 直接开会话（无需请求上下文），并把 LoginUser 绑进 Token 会话 */
    public static String loginToken(Long userId, String username) {
        String token = com.pivotos.starter.auth.account.StpSysUtil.STP.createLoginSession(userId);
        // getTokenSession() 依赖请求上下文，测试场景按 token 值直达会话
        com.pivotos.starter.auth.account.StpSysUtil.STP.getTokenSessionByToken(token)
                .set(com.pivotos.starter.auth.support.AuthSessionHolder.LOGIN_USER_KEY,
                        new LoginUser(userId, username,
                                com.pivotos.starter.auth.account.StpSysUtil.TYPE, null));
        return token;
    }
}
