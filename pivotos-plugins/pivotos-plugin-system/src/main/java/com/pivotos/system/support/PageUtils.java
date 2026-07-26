package com.pivotos.system.support;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pivotos.common.core.page.PageQuery;
import com.pivotos.common.core.page.PageResult;

import java.util.List;

/** 分页对象转换工具（PageQuery → MP Page → PageResult） */
public final class PageUtils {

    private PageUtils() {
    }

    /** PageQuery → MyBatis-Plus Page */
    public static <T> Page<T> toMpPage(PageQuery query) {
        return new Page<>(query.getPageNum(), query.getPageSize());
    }

    /** MP Page + 转换后的记录列表 → PageResult */
    public static <T, R> PageResult<R> toPageResult(Page<T> page, List<R> records) {
        return new PageResult<>(records, page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }
}
