package com.pivotos.server.cloud;

import com.pivotos.starter.cloud.api.facade.FacadeHttpPresets;
import com.pivotos.starter.cloud.api.facade.FacadeHttpWhitelist;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.util.ClassUtils;

import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Facade HTTP 预置只读组的静态守卫（V3-S4）。
 *
 * <p><b>为什么这个守卫必须放在 admin-server 而不是 starter-cloud-api</b>：
 * {@code starter-cloud-api} 是三通道契约包，<b>刻意不依赖任何 plugin 的 {@code *-api} 模块</b>，
 * 所以预置组里的接口名只能是字符串常量 —— 类名写错编译期不报错，
 * 运行期表现只是一个无从下手的 404。admin-server 能看见全部插件，
 * 是唯一能把「字符串 → 真实接口」这一步钉死的位置。
 *
 * <p>守三条红线：
 * <ol>
 *   <li>每个接口名都能解析成真实存在的接口（防拼写漂移）；</li>
 *   <li>每个显式列出的方法名在该接口上真实存在（防方法改名后白名单静默失效）；</li>
 *   <li><b>预置组里绝不能出现写方法</b> —— 这是「只读预置组」这个承诺的全部内容，
 *       一旦有人手滑把 {@code generateToProject} 加进来，只要部署方用了预置组，
 *       就等于开了「凭内部凭证远程写工程目录」的口子。</li>
 * </ol>
 */
class FacadeHttpPresetCatalogTest {

    /** 写方法黑名单：预置只读组里出现任何一个即视为破坏承诺 */
    private static final Set<String> FORBIDDEN_METHODS = Set.of(
        "send", "markRead", "markAllRead",
        "startInstance", "urgeInstance", "approveTask",
        "importTable", "generateToProject", "configureSubTable", "configureTreeTable",
        "presignUpload", "presignDownload",
        "chat", "chatWithSystem", "internalChat", "dynamicChat",
        "ensureIndex", "rebuildIndex", "invalidate"
    );

    @Test
    @DisplayName("预置组里的每个接口名都能解析成真实接口")
    void preset_interfaces_resolve_to_real_interfaces() {
        for (String entry : FacadeHttpPresets.READ_ONLY_BUSINESS) {
            String ifaceName = entry.split(":", 2)[0];
            Class<?> iface = ClassUtils.resolveClassName(ifaceName, getClass().getClassLoader());
            assertTrue(iface.isInterface(),
                "预置组里的必须是接口而不是实现类：" + ifaceName);
        }
    }

    @Test
    @DisplayName("预置组显式列出的方法名在该接口上真实存在")
    void preset_methods_exist_on_their_interface() {
        for (String entry : FacadeHttpPresets.READ_ONLY_BUSINESS) {
            String[] parts = entry.split(":", 2);
            if (parts.length < 2) {
                continue;
            }
            Class<?> iface = ClassUtils.resolveClassName(parts[0], getClass().getClassLoader());
            Set<String> declared = new LinkedHashSet<>();
            for (java.lang.reflect.Method method : iface.getMethods()) {
                declared.add(method.getName());
            }
            for (String method : parts[1].split(";")) {
                if (!method.isBlank()) {
                    assertTrue(declared.contains(method.trim()),
                        "预置组列出了接口上不存在的方法：" + parts[0] + "#" + method);
                }
            }
        }
    }

    /** 允许以「整个接口」形式开放的只读契约（逐个点名，新增必须在此登记） */
    private static final Set<String> FULLY_OPENED = Set.of(
        "com.pivotos.system.api.facade.IDeptFacade",
        "com.pivotos.system.api.facade.IDictFacade",
        "com.pivotos.system.api.facade.IRoleFacade",
        "com.pivotos.system.api.facade.IStatsFacade",
        "com.pivotos.system.api.facade.IUserFacade",
        "com.pivotos.ai.kb.api.facade.IKnowledgeBaseFacade");

    @Test
    @DisplayName("预置只读组实际开放的每一个方法都不是写方法")
    void preset_group_never_opens_write_methods() {
        for (String entry : FacadeHttpPresets.READ_ONLY_BUSINESS) {
            String[] parts = entry.split(":", 2);
            Class<?> iface = ClassUtils.resolveClassName(parts[0], getClass().getClassLoader());
            for (String method : effectiveMethods(iface, parts.length > 1 ? parts[1] : null)) {
                assertFalse(FORBIDDEN_METHODS.contains(method),
                    "预置只读组不该开放写方法：" + parts[0] + "#" + method);
            }
        }
    }

    @Test
    @DisplayName("以「整个接口」形式开放的条目必须逐个点名登记，不得随手新增")
    void interface_level_entries_must_be_explicitly_vetted() {
        for (String entry : FacadeHttpPresets.READ_ONLY_BUSINESS) {
            if (entry.contains(":")) {
                continue;
            }
            assertTrue(FULLY_OPENED.contains(entry),
                "新增「整个接口开放」的条目必须先确认该接口全是只读方法，并登记到 FULLY_OPENED：" + entry);
        }
        for (String ifaceName : FULLY_OPENED) {
            assertTrue(FacadeHttpPresets.READ_ONLY_BUSINESS.contains(ifaceName),
                "FULLY_OPENED 里登记了预置组里没有的接口（可能已被改成方法级）：" + ifaceName);
        }
    }

    /**
     * 条目实际开放的方法集合：显式列了方法名就是列出来的那些，否则是该接口的全部方法。
     *
     * <p><b>为什么必须按「实际方法集合」判，而不是拿黑名单去问白名单</b>：
     * 接口级条目会让 {@code allows(iface, "chat")} 对<b>该接口上根本不存在</b>的方法也返回 true
     * （写法：{@code IDeptFacade#chat}），那样断言只会产生假阳性，守不住东西。
     */
    private static Set<String> effectiveMethods(Class<?> iface, String declaredMethods) {
        Set<String> effective = new LinkedHashSet<>();
        if (declaredMethods != null) {
            for (String method : declaredMethods.split(";")) {
                if (!method.isBlank()) {
                    effective.add(method.trim());
                }
            }
            return effective;
        }
        for (java.lang.reflect.Method method : iface.getMethods()) {
            effective.add(method.getName());
        }
        return effective;
    }
}
