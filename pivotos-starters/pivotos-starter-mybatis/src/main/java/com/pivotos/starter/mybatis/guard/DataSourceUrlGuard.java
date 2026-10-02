package com.pivotos.starter.mybatis.guard;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * L9 清偿：数据源 URL「生效值 + 注入源」溯源。
 *
 * <p>历史欠账原文（S96 K1 升级）：「dev 环境 fat jar 进程偶现连接错误库 pivotos_asm，
 * 注入源穷举（环境变量三层 / git 历史 / jar 内配置 / 316MB 二进制扫描 / TRACE ConfigData）未定位；
 * 显式 CLI 覆盖 master.url 可稳定规避」——立项待查。
 *
 * <p>本轮不假装找到了当年的注入源（那是台已经不存在的机器上的进程），而是把「找不找得到」这件事
 * 从排障人的大脑搬进启动日志：<b>每次启动都打印生效库名与它是从哪个配置键读来的</b>，
 * 并给出可选的 {@code pivotos.mybatis.expected-database} fail-fast 断言。
 * 下次再出现「连错库」，第一眼就能看到「生效库=X、来源键=Y」，不必再穷举。
 *
 * <p>键顺序即 Spring Boot 的属性优先级在「我们关心的两个键」上的投影：
 * dynamic datasource 的 master 优先于单数据源的 url。
 */
public final class DataSourceUrlGuard {

    /** 候选配置键（按优先级从高到低） */
    public static final List<String> CANDIDATE_KEYS = List.of(
            "spring.datasource.dynamic.datasource.master.url",
            "spring.datasource.url");

    private DataSourceUrlGuard() {
    }

    /** 一次溯源的结果 */
    public record Report(String url, String database, String sourceKey, boolean resolved) {

        @Override
        public String toString() {
            return resolved
                    ? "database=" + database + ", sourceKey=" + sourceKey
                    : "unresolved（未在任何候选键上取到 JDBC URL）";
        }
    }

    /**
     * 解析生效的数据源 URL 及其注入源。
     *
     * @param environment Spring 环境（不可为 null）
     * @return 溯源报告；取不到时 {@code resolved=false}，其余字段为空串
     */
    public static Report resolve(Environment environment) {
        for (String key : CANDIDATE_KEYS) {
            String url = environment.getProperty(key);
            if (StringUtils.hasText(url)) {
                return new Report(url, databaseOf(url), key, true);
            }
        }
        return new Report("", "", "", false);
    }

    /**
     * 从 JDBC URL 中取库名：{@code jdbc:mysql://host:3306/db?x=y → db}。
     * 取不到一律返回空串（不抛异常——这里是旁路诊断，绝不能拖垮启动）。
     */
    public static String databaseOf(String jdbcUrl) {
        if (!StringUtils.hasText(jdbcUrl)) {
            return "";
        }
        int schemeEnd = jdbcUrl.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        int pathStart = jdbcUrl.indexOf('/', schemeEnd + 3);
        if (pathStart < 0) {
            return "";
        }
        String path = jdbcUrl.substring(pathStart + 1);
        int queryStart = path.indexOf('?');
        if (queryStart >= 0) {
            path = path.substring(0, queryStart);
        }
        int hashStart = path.indexOf('#');
        if (hashStart >= 0) {
            path = path.substring(0, hashStart);
        }
        return path;
    }

    /**
     * fail-fast 判定：配置了期望库名且与生效库名不一致时返回不一致说明，一致或无期望值返回 null。
     *
     * @param report           溯源结果
     * @param expectedDatabase 期望库名（{@code pivotos.mybatis.expected-database}），可空
     * @return 不一致说明；一致或无需校验时返回 null
     */
    public static String mismatch(Report report, String expectedDatabase) {
        if (!StringUtils.hasText(expectedDatabase)) {
            return null;
        }
        if (StringUtils.hasText(report.database()) && expectedDatabase.equals(report.database())) {
            return null;
        }
        return "期望库=" + expectedDatabase + "，生效库=" + (StringUtils.hasText(report.database()) ? report.database() : "<空>")
                + "，来源键=" + (StringUtils.hasText(report.sourceKey()) ? report.sourceKey() : "<无>");
    }
}
