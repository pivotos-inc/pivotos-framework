package com.pivotos.common.core.page;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分页查询入参，业务查询对象继承本类
 */
public class PageQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 单页最大条数上限，防恶意大分页 */
    private static final int MAX_PAGE_SIZE = 500;

    /** 页码，从 1 开始 */
    private Integer pageNum = 1;

    /** 每页条数 */
    private Integer pageSize = 10;

    public Integer getPageNum() {
        return (pageNum == null || pageNum < 1) ? 1 : pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        if (pageSize == null || pageSize < 1) {
            return 10;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }
}
