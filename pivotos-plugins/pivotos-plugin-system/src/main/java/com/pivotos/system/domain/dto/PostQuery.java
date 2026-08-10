package com.pivotos.system.domain.dto;

import lombok.Data;

/** 岗位查询参数 */
@Data
public class PostQuery {

    /** 岗位编码 */
    private String postCode;

    /** 岗位名称 */
    private String postName;

    /** 状态（0正常 1停用） */
    private Integer status;
}
