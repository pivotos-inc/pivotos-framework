package com.pivotos.docsync.api.dto;

import com.pivotos.docsync.api.enums.DocSyncTypeEnum;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serial;
import java.io.Serializable;

/**
 * 文档同步配置保存请求
 */
@Data
public class DocSyncConfigSaveRequest implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键（新增时为空） */
    @Schema(description = "主键（新增时为空）")
    private Long id;

    /** 配置名称 */
    @NotBlank(message = "配置名称不能为空")
    private String name;

    /** 平台类型 */
    @NotNull(message = "平台类型不能为空")
    private DocSyncTypeEnum platformType;

    /** 服务器地址（平台级校验，部分平台无需此字段） */
    @Schema(description = "服务器地址（平台级校验，部分平台无需此字段）")
    private String serverUrl;

    /** 认证凭证 */
    @Schema(description = "认证凭证")
    private String credential;

    /** 辅助凭证 */
    @Schema(description = "辅助凭证")
    private String secondaryCredential;

    /** 项目 ID */
    @Schema(description = "项目 ID")
    private String projectId;

    /** 是否启用（1 启用 0 禁用） */
    @Schema(description = "是否启用（1 启用 0 禁用）")
    private Integer enabled;

    /** 备注 */
    @Schema(description = "备注")
    private String remark;
}
