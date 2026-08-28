package com.pivotos.mind.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 知识库新建/修改请求 */
@Data
public class KnowledgeSaveBody {

    /** 标题 */
    @NotBlank(message = "标题不能为空")
    @Size(max = 200, message = "标题最长 200 字符")
    private String title;

    /** 类型：note / link / doc */
    @NotBlank(message = "类型不能为空")
    private String type;

    /** 内容 */
    @Size(max = 5000, message = "内容最长 5000 字符")
    private String content;

    /** 来源URL */
    @Size(max = 500, message = "链接最长 500 字符")
    private String sourceUrl;

    /** 标签，逗号分隔 */
    @Size(max = 200, message = "标签最长 200 字符")
    private String tags;
}
