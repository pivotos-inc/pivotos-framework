package com.pivotos.docsync.api.enums;

/**
 * 文档同步平台类型
 *
 * <p>每个枚举值对应一个独立部署的 API 文档管理平台，
 * 内嵌文档（Knife4j）不参与同步，仅作为本地渲染入口。
 */
public enum DocSyncTypeEnum {

    /** Torna — 企业级接口文档管理平台（支持自动同步，OpenAPI SDK 推送） */
    TORNA("Torna", true, "私有化部署，Java SDK，原生支持 OpenAPI JSON"),

    /** YApi — 开源 API 管理平台（支持自动同步，Swagger 导入 API） */
    YAPI("YApi", true, "私有化部署，支持 Swagger JSON URL 导入"),

    /** Apifox — API 文档/调试/Mock/测试一体化平台（支持自动同步，REST API 导入） */
    APIFOX("Apifox", true, "云端 SaaS，REST API 导入 OpenAPI"),

    /** ShowDoc — 在线 API/技术文档工具（支持自动同步，需 OpenAPI→Markdown 转换） */
    SHOWDOC("ShowDoc", true, "私有化部署，开放 API 接受 Markdown 内容"),

    /** XXL-API — API 管理平台（不支持自动同步，仅手动导出+跳转） */
    XXL_API("XXL-API", false, "无私有化开放 API，仅支持手动同步"),

    /** ApiPost — API 研发协同平台（开放 API 待验证，暂手动同步） */
    APIPOST("ApiPost", false, "私有化部署，开放 API 待验证"),

    /** Eolink — 一站式 API 管理平台（开放 API 待验证，暂手动同步） */
    EOLINK("Eolink", false, "商业 SaaS，开放 API 待验证"),
    ;

    /** 平台显示名 */
    private final String displayName;

    /** 是否支持自动同步 */
    private final boolean autoSyncSupported;

    /** 平台特性描述 */
    private final String description;

    DocSyncTypeEnum(String displayName, boolean autoSyncSupported, String description) {
        this.displayName = displayName;
        this.autoSyncSupported = autoSyncSupported;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public boolean isAutoSyncSupported() {
        return autoSyncSupported;
    }

    public String getDescription() {
        return description;
    }
}
