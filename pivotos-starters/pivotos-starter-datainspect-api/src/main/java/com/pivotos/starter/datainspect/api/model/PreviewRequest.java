package com.pivotos.starter.datainspect.api.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 预览请求：由前端从库表树点选而来，<b>不接受用户语句</b>，SQL 由实现内部固定拼装。
 *
 * @author PivotOS Team
 * @since 2.15.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreviewRequest {

    private String component;

    private String schema;

    private String table;

    @Builder.Default
    private int pageNum = 1;

    @Builder.Default
    private int pageSize = 20;
}
