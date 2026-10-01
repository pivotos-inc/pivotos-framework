package com.pivotos.starter.search.esjava.support;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.InfoResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动期服务端版本探测。
 * <p>只做一件事：{@code GET /} 拿 version.number。<b>任何异常都不外抛</b>——
 * 探测是旁路，ES 不可达或兼容头被服务端拒绝时返回 {@link EsServerVersion#UNKNOWN}，
 * 由 Provider 自行决定回落，绝不拖垮应用启动。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
public final class EsServerVersionProbe {

    private static final Logger log = LoggerFactory.getLogger(EsServerVersionProbe.class);

    private EsServerVersionProbe() {
    }

    public static EsServerVersion probe(ElasticsearchClient client) {
        if (client == null) {
            return EsServerVersion.UNKNOWN;
        }
        try {
            InfoResponse info = client.info();
            String number = info != null && info.version() != null ? info.version().number() : null;
            EsServerVersion version = EsServerVersion.parse(number);
            if (version.isUnknown()) {
                log.warn("[PivotOS][search] es-java 服务端版本探测失败：version.number={}（解析不出版本号，按不支持处理）", number);
            } else {
                log.info("[PivotOS][search] es-java 服务端版本探测：{}（支持区间 {}）",
                        version.raw(), EsServerVersion.supportRangeText());
            }
            return version;
        } catch (Exception e) {
            // 不可达 / 认证失败 / 兼容头被拒（media_type_header_exception）一律按「探测不到」处理
            log.warn("[PivotOS][search] es-java 服务端版本探测失败（ES 不可达或兼容头被服务端拒绝）：{}", e.getMessage());
            return EsServerVersion.UNKNOWN;
        }
    }
}
