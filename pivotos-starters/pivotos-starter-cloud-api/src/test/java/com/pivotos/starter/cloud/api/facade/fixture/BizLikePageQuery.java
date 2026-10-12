package com.pivotos.starter.cloud.api.facade.fixture;

import com.pivotos.common.core.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * 与 {@code MessagePageQuery} 同构的查询对象（继承 {@link PageQuery}，带分页字段）。
 */
@Data
@EqualsAndHashCode(callSuper = true)
public class BizLikePageQuery extends PageQuery {

    /** 已读状态（0 未读 1 已读，null 全部） */
    private Integer readStatus;

    /** 消息类型（null 全部） */
    private Integer msgType;
}
