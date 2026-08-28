package com.pivotos.mind.api.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 知识库条目视图 */
@Data
public class KnowledgeVO {

    private Long id;
    private String title;
    private String type;
    private String content;
    private String sourceUrl;
    private String tags;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
