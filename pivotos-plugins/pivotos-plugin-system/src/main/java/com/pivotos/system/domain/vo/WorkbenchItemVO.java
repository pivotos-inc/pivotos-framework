package com.pivotos.system.domain.vo;

import lombok.Data;

/** 移动端工作台宫格项 */
@Data
public class WorkbenchItemVO {

    /** 菜单ID */
    private Long id;

    /** 应用名称 */
    private String menuName;

    /** 图标（宫格渲染标识） */
    private String icon;

    /** 移动端页面路径（如 /pages/notice/notice） */
    private String path;

    /** 显示顺序 */
    private Integer sort;
}
