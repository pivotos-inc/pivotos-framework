package com.pivotos.system.domain.vo;

import lombok.Data;

import java.util.List;

/** 动态路由视图对象（由菜单树中 M/C 类型节点构建） */
@Data
public class RouterVO {

    /** 路由名 */
    private String name;

    /** 路由地址 */
    private String path;

    /** 组件路径（目录为 Layout） */
    private String component;

    /** 是否隐藏 */
    private Boolean hidden;

    /** 元信息 */
    private Meta meta;

    /** 子路由 */
    private List<RouterVO> children;

    /** 路由元信息 */
    @Data
    public static class Meta {

        /** 标题（菜单名） */
        private String title;

        /** 图标 */
        private String icon;
    }
}
