package com.pivotos.docsync.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 平台配置字段描述
 *
 * <p>每个同步平台所需的配置字段不同，通过此 DTO 向前端声明各字段的
 * key、标签、是否必填、输入类型、占位提示等信息，供前端动态渲染表单。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DocSyncConfigFieldDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 字段 key（对应 DocSyncConfig 属性名） */
    private String fieldKey;

    /** 字段标签（如"项目 Token"） */
    private String label;

    /** 是否必填 */
    private boolean required;

    /** 输入类型：text / textarea / url / password */
    private String inputType;

    /** 占位提示 */
    private String placeholder;

    /** 字段说明 */
    private String description;
}
