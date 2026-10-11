package com.pivotos.starter.mybatis.guard;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * 字段加密密钥的启动期强度守卫（v3.1.0 生产化 · V3.1-D 遗留③ / C3-1）。
 *
 * <p>要解决的问题：{@code application.yml} 里写的是
 * {@code field-encrypt-key: ${PIVOTOS_FIELD_ENCRYPT_KEY:pivotos-dev-key!}}——
 * 这是一个<b>开发默认兜底</b>。它让本地零配置可跑（好），但也意味着：
 * 生产部署只要忘了注入 {@code PIVOTOS_FIELD_ENCRYPT_KEY}，应用会<b>正常启动、正常加解密</b>，
 * 只是用的是一个**公开在 git 仓库里的密钥**——字段加密形同没有，且完全没有信号。
 *
 * <p>口径（拍板 C3-1）：
 * <ol>
 *   <li>profile 为 {@code prod} 且解析到的密钥等于开发默认值 → <b>拒绝启动</b>（抛异常，进程非零退出）；</li>
 *   <li>其余形态（dev/test/未指定）命中开发默认值 → 只打 WARN，行为<b>零变化</b>（IT 与本地开发不受影响）；</li>
 *   <li>未配置密钥（字段加密不启用）→ 不管，交给 {@code FieldEncryptCrypto#isEnabled()} 的既有语义；</li>
 *   <li>紧急绕过开关 {@code pivotos.mybatis.field-encrypt-key-guard-enabled=false}
 *       （环境变量 {@code PIVOTOS_FIELD_ENCRYPT_KEY_GUARD_ENABLED}）——运维在事故窗口里可显式摘除，
 *       但每一次绕过都会在 WARN 日志里留痕。</li>
 * </ol>
 *
 * <p>为什么不做「dev 也拒启动」：dev/test 的零配置起服是 IT 与本地开发的前提，
 * 一刀切会让 8 个测试库上的全量回归直接起不来。
 */
public final class FieldEncryptKeyGuard {

    /** application.yml 里 {@code ${PIVOTOS_FIELD_ENCRYPT_KEY:...}} 的开发默认兜底值（明文进 git，生产禁用） */
    public static final String DEV_DEFAULT_KEY = "pivotos-dev-key!";

    /** 需要强校验的 profile */
    public static final String PROD_PROFILE = "prod";

    /** 紧急绕过开关的键 */
    public static final String GUARD_ENABLED_KEY = "pivotos.mybatis.field-encrypt-key-guard-enabled";

    private FieldEncryptKeyGuard() {
    }

    /** 密钥的三种判定结果 */
    public enum Verdict {
        /** 通过：自定义密钥，或未启用字段加密 */
        OK,
        /** 仅告警：非 prod 形态使用了开发默认密钥 */
        DEV_DEFAULT_WARN,
        /** 拒绝启动：prod 形态仍在使用开发默认密钥 */
        PROD_DEFAULT_REJECTED
    }

    /**
     * 纯函数判定（便于单测钉死口径，不依赖 Spring 上下文）。
     *
     * @param prodActive prod profile 是否生效
     * @param key        解析到的密钥（可能为 null/空，代表未启用字段加密）
     * @param guardOn    守卫总开关
     * @return 判定结果
     */
    public static Verdict evaluate(boolean prodActive, String key, boolean guardOn) {
        if (!guardOn || !StringUtils.hasText(key) || !DEV_DEFAULT_KEY.equals(key)) {
            return Verdict.OK;
        }
        return prodActive ? Verdict.PROD_DEFAULT_REJECTED : Verdict.DEV_DEFAULT_WARN;
    }

    /**
     * 启动期 fail-fast 判定。
     *
     * @param environment Spring 环境
     * @param key         解析到的密钥
     * @return 需要拒绝启动时返回给运维看的说明（含怎么改）；否则返回 null
     */
    public static String rejection(Environment environment, String key) {
        boolean guardOn = environment.getProperty(GUARD_ENABLED_KEY, Boolean.class, true);
        boolean prodActive = environment.matchesProfiles(PROD_PROFILE);
        if (evaluate(prodActive, key, guardOn) != Verdict.PROD_DEFAULT_REJECTED) {
            return null;
        }
        return "prod 形态仍在使用开发默认字段加密密钥（pivotos-dev-key!），该值明文进 git，"
                + "等于字段加密未生效。请注入 PIVOTOS_FIELD_ENCRYPT_KEY（16/24/32 位字符串）后重启；"
                + "确需紧急绕过可临时设置 " + GUARD_ENABLED_KEY + "=false（会被 WARN 记录）。";
    }

    /** 是否正在使用开发默认密钥（用于非 prod 形态的 WARN） */
    public static boolean usesDevDefault(String key) {
        return DEV_DEFAULT_KEY.equals(key);
    }
}
