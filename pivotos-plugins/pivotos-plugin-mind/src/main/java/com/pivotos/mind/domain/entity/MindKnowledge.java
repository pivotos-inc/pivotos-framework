package com.pivotos.mind.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.BaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * PivotOS·智域知识库条目
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mind_knowledge")
public class MindKnowledge extends BaseDO {

    /** 所属用户ID */
    private Long userId;

    /** 标题 */
    private String title;

    /** 类型：note 笔记 / link 链接 / doc 文档 */
    private String type;

    /** 内容（Markdown / 链接 / 摘要） */
    private String content;

    /** 来源URL（link/doc 类型） */
    private String sourceUrl;

    /** 标签，逗号分隔 */
    private String tags;
}
