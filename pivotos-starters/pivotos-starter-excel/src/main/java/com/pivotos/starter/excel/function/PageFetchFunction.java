package com.pivotos.starter.excel.function;

import java.util.List;

/**
 * 分页数据获取接口：导出时由业务模块实现，ExcelHelper 按页拉取数据以支持大数量流式写入。
 *
 * @param <T> 数据类型
 * @author PivotOS Team
 * @since 2.1.0
 */
@FunctionalInterface
public interface PageFetchFunction<T> {

    /**
     * 获取第 pageNum 页数据
     *
     * @param pageNum  页码（从 1 开始）
     * @param pageSize 每页条数
     * @return 当前页数据列表，空列表表示没有更多数据
     */
    List<T> fetch(int pageNum, int pageSize);
}
