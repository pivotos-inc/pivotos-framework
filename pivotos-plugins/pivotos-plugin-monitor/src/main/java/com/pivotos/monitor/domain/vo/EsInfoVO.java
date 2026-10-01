package com.pivotos.monitor.domain.vo;

import com.pivotos.starter.search.api.health.SearchHealthSnapshot;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * ES 监控快照（系统监控 · ES 监控页）。
 * <p><b>为什么继承而不是抄一份字段</b>：指标由 {@code starter-search-api} 的
 * {@link SearchHealthSnapshot} 契约承载（monitor 只引契约包，不能依赖 -es-java 实现模块），
 * 抄一份字段会在契约演进时出现「两边不同步」的静默漂移，故直接继承、由拷贝构造搬运。
 * <p>需要新增监控侧专有字段时在这里加即可，契约字段永远只有一份。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "ES 监控快照")
public class EsInfoVO extends SearchHealthSnapshot {

    public EsInfoVO() {
    }

    public EsInfoVO(SearchHealthSnapshot src) {
        super(src);
    }
}
