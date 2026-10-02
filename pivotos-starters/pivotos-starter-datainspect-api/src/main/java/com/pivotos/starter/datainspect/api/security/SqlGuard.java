package com.pivotos.starter.datainspect.api.security;

/**
 * SQL 安全闸门契约（实现放在主 Starter，契约放 api 便于跨实现复用与单测）。
 *
 * <p><b>铁律</b>：闸门是<b>硬生效</b>的安全边界，与权限（Sa-Token 注解）互不替代。
 * 即使是超管，DROP / DELETE / UPDATE / INSERT / ALTER / TRUNCATE / GRANT / 多语句 / 注释绕过 也一律拒绝。
 *
 * <p>校验顺序（任一不过即拒）：
 * <ol>
 *   <li>非空与长度上限；</li>
 *   <li>单语句（防堆叠 {@code ;}）；</li>
 *   <li>剥离注释（含 MySQL 可执行注释 {@code /*! ... *}{@code /}）后必须仍能解析；</li>
 *   <li>语句类型白名单：仅 SELECT（EXPLAIN 走单独分支）；</li>
 *   <li>函数黑名单（SLEEP / BENCHMARK / LOAD_FILE ...）；</li>
 *   <li>库表白名单；</li>
 *   <li>强制 LIMIT（无则注入、有则收紧）；</li>
 *   <li>租户改写（非忽略表且存在租户列 → AND tenant_id = ?）。</li>
 * </ol>
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
public interface SqlGuard {

    /**
     * 校验并改写语句。
     *
     * @param sql     用户原始语句
     * @param context 闸门上下文
     * @return 通过 / 拒绝结果；<b>永不抛异常</b>，解析异常一律按拒绝处理
     */
    GuardResult inspect(String sql, GuardContext context);
}
