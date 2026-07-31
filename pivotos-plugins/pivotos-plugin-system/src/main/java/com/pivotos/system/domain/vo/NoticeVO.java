package com.pivotos.system.domain.vo;

import com.pivotos.common.api.dto.BaseDTO;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/** 通知公告视图对象 */
@Data
@EqualsAndHashCode(callSuper = true)
public class NoticeVO extends BaseDTO {

    /** 公告标题 */
    private String title;

    /** 类型（1通知 2公告） */
    private Integer noticeType;

    /** 富文本内容（HTML） */
    private String content;

    /** 状态（0草稿 1已发布 2已撤回） */
    private Integer status;

    /** 发布时间 */
    private LocalDateTime publishTime;

    /** 备注 */
    private String remark;
}
