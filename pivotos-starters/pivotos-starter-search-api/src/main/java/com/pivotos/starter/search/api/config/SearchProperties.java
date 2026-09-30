package com.pivotos.starter.search.api.config;

import lombok.Data;

import java.util.List;

/**
 * 搜索配置（{@code pivotos.search.*}）。
 * <p>这里刻意写成<b>普通 POJO 不挂 @ConfigurationProperties</b>——契约包保持零 Spring 依赖，
 * 由主 Starter 在 @Bean 方法上完成绑定（Spring Boot 支持 bean 方法级绑定）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Data
public class SearchProperties {

    /**
     * 是否启用搜索 Starter（默认 true：simple 是纯内存实现，敞开无副作用）
     */
    private boolean enabled = true;

    /**
     * 实现类型：simple / easy-es / es-java。
     * 未命中已注册 Provider 时回落到 simple 并打 WARN（保证配错也能起服）
     */
    private String type = "simple";

    /**
     * 索引名前缀（多环境共用一套 ES 时隔离用，为空则不加前缀）
     */
    private String indexPrefix = "";

    /** Easy-ES 实现配置 */
    private EasyEs easyEs = new EasyEs();

    /** elasticsearch-java 实现配置 */
    private EsJava esJava = new EsJava();

    /**
     * Easy-ES 实现（easy-es-core 3.0.2，内嵌 elasticsearch-java 7.17.28，面向 ES 7.17）
     */
    @Data
    public static class EasyEs {

        /** ES 节点地址 */
        private List<String> uris = List.of("http://localhost:9200");

        /** 用户名（无认证时留空） */
        private String username;

        /** 密码 */
        private String password;

        /** 连接超时（毫秒） */
        private int connectTimeout = 3000;

        /** 读取超时（毫秒） */
        private int socketTimeout = 30000;
    }

    /**
     * elasticsearch-java 实现（官方新客户端，面向 ES 8.x/9.x）
     */
    @Data
    public static class EsJava {

        /** ES 节点地址 */
        private List<String> uris = List.of("http://localhost:9200");

        /** 用户名（无认证时留空） */
        private String username;

        /** 密码 */
        private String password;

        /** 连接超时（毫秒） */
        private int connectTimeout = 3000;

        /** 读取超时（毫秒） */
        private int socketTimeout = 30000;

        /**
         * 兼容模式：用 8.x 客户端连 7.17 服务端时置 true（官方 compatibility header）
         */
        private boolean compatibilityMode = false;
    }
}
