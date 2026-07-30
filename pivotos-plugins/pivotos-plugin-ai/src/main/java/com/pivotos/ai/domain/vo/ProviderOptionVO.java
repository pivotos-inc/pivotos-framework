package com.pivotos.ai.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 供应商选项（对话页下拉，登录即可见，不暴露 base-url 等管理信息） */
@Data
@AllArgsConstructor
public class ProviderOptionVO {

    /** 供应商ID */
    private Long id;

    /** 供应商名称 */
    private String name;

    /** 默认模型 */
    private String defaultModel;
}
