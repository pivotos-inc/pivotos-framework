package com.pivotos.starter.search.fixture;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 主 Starter 测试实体。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OperLogDoc {

    private Long id;

    private String title;

    private Integer status;

    private Boolean enabled;

    private LocalDateTime operTime;
}
