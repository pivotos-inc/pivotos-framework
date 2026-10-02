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

    /**
     * 组件路径：
     * - 顶层目录 → {@code Layout}（套完整布局框架）
     * - 非顶层目录（目录下挂目录）→ 空串（前端按纯路由容器渲染，不重复套布局）
     * - 菜单（C 类型）→ views 下的组件相对路径
     */
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
