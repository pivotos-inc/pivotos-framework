package com.pivotos.mind.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

/** 待办新建/修改请求 */
@Data
public class TodoSaveBody {

    /** 待办标题 */
    @NotBlank(message = "待办内容不能为空")
    @Size(max = 200, message = "标题最长 200 字符")
    private String title;

    /** 备注 */
    @Size(max = 500, message = "备注最长 500 字符")
    private String remark;

    /** 优先级：low / medium / high */
    private String priority;

    /** 计划完成时间 */
    private LocalDateTime dueTime;
}
