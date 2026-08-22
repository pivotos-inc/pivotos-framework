package com.pivotos.ai.kb.domain.dto;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serial;

/**
 * 知识库文档分页查询。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class KbDocPageQuery extends PageQuery {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 知识库 ID */
    @Schema(description = "知识库 ID")
    private Long kbId;

    /** 文件名模糊查询 */
    @Schema(description = "文件名模糊查询")
    private String fileName;

    /** 文档状态 */
    @Schema(description = "文档状态")
    private Integer status;
}
