package com.pivotos.mind.api.vo;

import lombok.Data;

import java.time.LocalDateTime;

/** 待办事项视图 */
@Data
public class TodoVO {

    private Long id;
    private String title;
    private String remark;
    private String priority;
    private Integer status;
    private LocalDateTime dueTime;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
}
