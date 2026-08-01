package com.pivotos.system.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** 岗位新增/修改请求 */
@Data
public class PostSaveRequest {

    /** 岗位ID（修改时必传） */
    private Long id;

    /** 岗位编码 */
    @NotBlank(message = "岗位编码不能为空")
    @Size(max = 64, message = "岗位编码长度不能超过64个字符")
    private String postCode;

    /** 岗位名称 */
    @NotBlank(message = "岗位名称不能为空")
    @Size(max = 64, message = "岗位名称长度不能超过64个字符")
    private String postName;

    /** 显示顺序 */
    private Integer sort;

    /** 状态（0正常 1停用） */
    private Integer status;

    /** 备注 */
    @Size(max = 500, message = "备注长度不能超过500个字符")
    private String remark;
}
