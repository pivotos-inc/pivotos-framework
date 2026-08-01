package com.pivotos.starter.excel.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Excel 导入导出配置属性
 *
 * @author PivotOS Team
 * @since 2.1.0
 */
@Data
@ConfigurationProperties(prefix = "pivotos.excel")
public class ExcelProperties {

    /** 是否启用 Excel 能力，默认 true */
    private boolean enabled = true;

    /** 导出的每页大小（分页写入），默认 500 */
    private int exportPageSize = 500;

    /** 导入的每批写入大小（校验通过后分批入库），默认 300 */
    private int importBatchSize = 300;

    /** 导入时单文件最大行数限制，默认 10000 */
    private int importMaxRows = 10000;
}
