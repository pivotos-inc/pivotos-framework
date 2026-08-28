package com.pivotos.mind.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** AI 智能创建待办请求 */
@Data
public class AiTodoBody {

    /** 自然语言描述 */
    @NotBlank(message = "描述不能为空")
    @Size(max = 1000, message = "描述最长 1000 字符")
    private String description;
}
