package com.pivotos.starter.cloud.api.facade;

import com.pivotos.starter.cloud.api.facade.fixture.BizLikeFacade;
import com.pivotos.starter.cloud.api.facade.fixture.BizLikeLocalFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FacadeHttpWhitelist} 的纯计算层断言（不依赖 Web 容器，跑得快）。
 *
 * <p><b>为什么白名单解析要单独有一组单测</b>：它是「写方法会不会被误开放」的
 * <b>唯一</b>判定点 —— 解析错一个字符的代价是 {@code generateToProject} 变成可远程调用。
 * 这类逻辑必须红绿双向可证，不能只靠端到端那条路顺带覆盖。
 */
class FacadeHttpWhitelistTest {

    /** 同名同元数重载：按「名+元数」定位时必有一个永远调不到 */
    interface OverloadedFacade {

        void run(String a);

        void run(Integer a);
    }

    @Test
    @DisplayName("空白名单拒绝一切")
    void empty_whitelist_rejects_everything() {
        FacadeHttpWhitelist whitelist = FacadeHttpWhitelist.parse(null);

        assertThat(whitelist.isEmpty()).isTrue();
        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "stats")).isFalse();
    }

    @Test
    @DisplayName("只写接口名 = 该接口全部方法可调用")
    void interface_only_entry_opens_all_methods() {
        FacadeHttpWhitelist whitelist = FacadeHttpWhitelist.parse(List.of(BizLikeFacade.class.getName()));

        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "stats")).isTrue();
        // 写方法也在里面：这就是「只给接口级粒度」的危险之处，也是方法级粒度存在的理由
        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "preview")).isTrue();
    }

    @Test
    @DisplayName("接口名:方法名 = 只有列出的方法可调用")
    void method_level_entry_restricts_to_listed_methods() {
        FacadeHttpWhitelist whitelist =
            FacadeHttpWhitelist.parse(List.of(BizLikeFacade.class.getName() + ":stats;readFile"));

        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "stats")).isTrue();
        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "readFile")).isTrue();
        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "preview")).isFalse();
    }

    @Test
    @DisplayName("同一个接口写两行时合并方法集合，而不是后者覆盖前者")
    void duplicate_interface_entries_are_merged() {
        FacadeHttpWhitelist whitelist = FacadeHttpWhitelist.parse(List.of(
            BizLikeFacade.class.getName() + ":stats",
            BizLikeFacade.class.getName() + ":readFile"));

        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "stats")).isTrue();
        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "readFile")).isTrue();
        assertThat(whitelist.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("空条目与空白方法名被忽略，不会因为一个多余的分号就全开")
    void blank_entries_are_ignored() {
        FacadeHttpWhitelist whitelist = FacadeHttpWhitelist.parse(List.of(
            "", "   ", BizLikeFacade.class.getName() + ":;"));

        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "preview")).isTrue();
        assertThat(whitelist.size()).isEqualTo(1);
    }

    @Test
    @DisplayName("单个字符串按逗号切条目、按分号切方法（Spring List 绑定的真实形态）")
    void single_string_entry_splits_on_comma_then_semicolon() {
        // 模拟 Spring 把环境变量 "A:m1;m2,B" 绑成 List 之后的产物
        FacadeHttpWhitelist whitelist = FacadeHttpWhitelist.parse(List.of(
            BizLikeFacade.class.getName() + ":stats;readFile",
            "com.pivotos.other.IOtherFacade"));

        assertThat(whitelist.size()).isEqualTo(2);
        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "stats")).isTrue();
        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "readFile")).isTrue();
        assertThat(whitelist.allows(BizLikeFacade.class.getName(), "preview")).isFalse();
        assertThat(whitelist.allows("com.pivotos.other.IOtherFacade", "anything")).isTrue();
    }

    @Test
    @DisplayName("自检：类名写错 / 方法名写错 / 同名同元数重载都会被点名")
    void inspect_reports_misconfiguration() {
        FacadeHttpWhitelist whitelist = FacadeHttpWhitelist.parse(List.of(
            "com.pivotos.NoSuchFacade",
            BizLikeFacade.class.getName() + ":noSuchMethod",
            OverloadedFacade.class.getName()));

        List<String> warnings = whitelist.inspect(emptyContext());

        assertThat(warnings).anyMatch(w -> w.contains("com.pivotos.NoSuchFacade")
            && w.contains("classpath 上找不到该接口"));
        assertThat(warnings).anyMatch(w -> w.contains("noSuchMethod") && w.contains("不存在"));
        assertThat(warnings).anyMatch(w -> w.contains("run(1 参)") && w.contains("同名同元数"));
        assertThat(warnings).anyMatch(w -> w.contains("没有该 Facade 的实现 Bean"));
    }

    @Test
    @DisplayName("自检：接口存在且有实现 Bean 时不产生告警（避免日志噪音掩盖真问题）")
    void inspect_stays_silent_when_wiring_is_correct() {
        GenericApplicationContext context = emptyContext();
        context.getBeanFactory().registerSingleton("bizLikeFacade", new BizLikeLocalFacade());
        FacadeHttpWhitelist whitelist =
            FacadeHttpWhitelist.parse(List.of(BizLikeFacade.class.getName() + ":stats"));

        assertThat(whitelist.inspect(context)).isEmpty();
    }

    /**
     * {@code GenericApplicationContext} 必须 {@code refresh()} 之后才能查 Bean
     * （否则 {@code getBeanNamesForType} 抛 IllegalStateException）。
     */
    private static GenericApplicationContext emptyContext() {
        GenericApplicationContext context = new GenericApplicationContext();
        context.refresh();
        return context;
    }
}
