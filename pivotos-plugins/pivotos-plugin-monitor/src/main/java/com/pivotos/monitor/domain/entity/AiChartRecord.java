package com.pivotos.monitor.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.pivotos.starter.mybatis.domain.TenantBaseDO;
import lombok.Data;
import lombok.EqualsAndHashCode;

/**
 * AI 图表历史实体（S83 报表大屏四期）。
 * <p>
 * 保存用户主动收藏的 AI 图表规格（S72 ChartSpec 结构化输出），
 * spec_json 存完整 ChartSpec JSON，前端回放时按 chartType 确定性装配渲染。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("mn_ai_chart")
public class AiChartRecord extends TenantBaseDO {

    private static final long serialVersionUID = 1L;

    /** 归属用户 ID */
    private Long userId;

    /** 生成时的自然语言描述 */
    private String question;

    /** 图表标题（冗余自 spec，便于列表展示） */
    private String title;

    /** 图表类型：line / bar / pie（白名单） */
    private String chartType;

    /** ChartSpec JSON（title/chartType/categories/series/explanation） */
    private String specJson;
}
