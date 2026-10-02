package com.pivotos.system;

import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * saveBatch 真落库守卫（S125 回归批实测缺陷的回归门禁）。
 *
 * <p>经过：{@code warm-flow-mybatis-plus-sb4-starter 1.8.7} 直接传递依赖
 * {@code com.baomidou:mybatis-plus-extension:3.5.15}，而
 * {@code mybatis-plus-spring-boot4-starter 3.5.17} 不传递引入 extension 模块，
 * 于是 classpath 上 extension 只剩 3.5.15。3.5.17 的
 * {@code spring.repository.CrudRepository} 继承 extension 的
 * {@code AbstractRepository}，其 {@code saveBatch} 调用 3.5.17 才有的重载
 * {@code executeBatch(Collection, int, BiFunction)}，而 3.5.15 没有它 ——
 * 运行期抛 {@link NoSuchMethodError}，凡走 {@code IService#saveBatch} 的链路 100% 失败
 * （实测 {@code POST /system/user/import} 恒「系统内部错误」，用户导入整条链路不可用）。
 *
 * <p>这类缺陷**编译期与单测 mock 都发现不了**（签名在编译期存在，只在真实 classpath 上缺失），
 * 必须有「真落库一次 saveBatch」的用例钉住。修复方式见
 * {@code pivotos-dependencies} 中对 {@code mybatis-plus-extension} 的版本仲裁。
 */
@SpringBootTest(classes = SystemTestApplication.class)
class SaveBatchClasspathGuardTest {

    @Autowired
    private UserService userService;

    @Test
    void saveBatch_insertsRows_insteadOfNoSuchMethodError() {
        String stamp = "s125sb" + System.currentTimeMillis();
        List<SysUser> batch = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            SysUser user = new SysUser();
            user.setUsername(stamp + "_" + i);
            user.setNickname("批量守卫" + i);
            user.setPassword("guard123456");
            user.setStatus(0);
            user.setDeptId(0L);
            batch.add(user);
        }

        long before = userService.lambdaQuery()
                .likeRight(SysUser::getUsername, stamp)
                .count();

        // 若 classpath 上 extension 与 spring 版本错配，这一行抛 NoSuchMethodError
        boolean saved = userService.saveBatch(batch);

        try {
            assertThat(saved).isTrue();
            long after = userService.lambdaQuery()
                    .likeRight(SysUser::getUsername, stamp)
                    .count();
            assertThat(after).isEqualTo(before + 3);
            assertThat(batch).allSatisfy(u -> assertThat(u.getId()).isNotNull());
        } finally {
            List<Long> ids = new ArrayList<>();
            for (SysUser u : batch) {
                if (u.getId() != null) {
                    ids.add(u.getId());
                }
            }
            if (!ids.isEmpty()) {
                userService.removeBatchByIds(ids);
            }
            long cleaned = userService.lambdaQuery()
                    .likeRight(SysUser::getUsername, stamp)
                    .count();
            assertThat(cleaned).isEqualTo(before);
        }
    }
}
