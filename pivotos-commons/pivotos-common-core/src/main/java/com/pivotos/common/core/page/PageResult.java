package com.pivotos.common.core.page;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果
 *
 * @param <T> 行数据类型
 */
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> list = Collections.emptyList();

    /** 总条数 */
    private Long total = 0L;

    /** 页码 */
    private Integer pageNum;

    /** 每页条数 */
    private Integer pageSize;

    public PageResult() {
    }

    public PageResult(List<T> list, Long total, Integer pageNum, Integer pageSize) {
        this.list = list == null ? Collections.emptyList() : list;
        this.total = total == null ? 0L : total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    /**
     * 空结果
     */
    public static <T> PageResult<T> empty() {
        return new PageResult<>();
    }

    /**
     * 总页数
     */
    public long getPages() {
        if (pageSize == null || pageSize <= 0) {
            return 0;
        }
        return (total + pageSize - 1) / pageSize;
    }

    public List<T> getList() {
        return list;
    }

    public void setList(List<T> list) {
        this.list = list;
    }

    public Long getTotal() {
        return total;
    }

    public void setTotal(Long total) {
        this.total = total;
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }
}
