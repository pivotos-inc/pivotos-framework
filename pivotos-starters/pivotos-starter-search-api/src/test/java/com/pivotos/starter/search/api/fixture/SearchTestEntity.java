package com.pivotos.starter.search.api.fixture;

import com.pivotos.starter.search.api.annotation.SearchIndex;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 契约层测试实体（无第三方依赖，可跨模块复用形态参照）。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@SearchIndex("search-test-entity")
public class SearchTestEntity {

    private Long id;

    private String title;

    private Integer status;

    private Boolean enabled;

    private LocalDateTime operTime;
}
