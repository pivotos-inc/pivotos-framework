package com.pivotos.system.domain.dto;

import lombok.Data;

/** 菜单查询（树形列表，不分页） */
@Data
public class MenuQuery {

    /** 菜单名称（模糊） */
    private String menuName;

    /** 状态 */
    private Integer status;
}
