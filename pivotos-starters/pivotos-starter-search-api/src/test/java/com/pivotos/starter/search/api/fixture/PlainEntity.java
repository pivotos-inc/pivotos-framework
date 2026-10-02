package com.pivotos.starter.search.api.fixture;

import lombok.Data;

/**
 * 无 {@code @SearchIndex} 注解的实体：用于验证「类名转 kebab-case」兜底。
 *
 * @author PivotOS Team
 * @since 2.16.0
 */
@Data
public class PlainEntity {

    private Long id;

    private String name;
}
