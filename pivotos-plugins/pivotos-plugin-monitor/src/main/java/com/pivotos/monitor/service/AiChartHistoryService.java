package com.pivotos.monitor.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import tools.jackson.databind.ObjectMapper;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.monitor.domain.dto.AiChartSaveCmd;
import com.pivotos.monitor.domain.entity.AiChartRecord;
import com.pivotos.monitor.domain.vo.AiChartHistoryVO;
import com.pivotos.monitor.domain.vo.AiChartSpecVO;
import com.pivotos.monitor.mapper.AiChartRecordMapper;
import com.pivotos.starter.core.context.LoginContext;
import com.pivotos.starter.core.context.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Set;

/**
 * AI 图表历史服务（S83 报表大屏四期）：收藏 / 我的历史分页 / 删除。
 * <p>
 * 数据归属强校验：仅保存人可见可删（user_id 过滤 + 删除前归属核对）。
 * spec 落库为 ChartSpec JSON 全量快照，回放时前端按 chartType 确定性装配，
 * 与 S72 生成链路同口径（禁直渲 raw option）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiChartHistoryService {

    /** chartType 白名单（与 AiChartService 生成校验同口径） */
    private static final Set<String> CHART_TYPES = Set.of("line", "bar", "pie");

    private final AiChartRecordMapper aiChartRecordMapper;
    private final ObjectMapper objectMapper;

    /** 收藏当前生成的图表（spec 校验与生成端点同口径） */
    public Long save(AiChartSaveCmd cmd) {
        Long userId = requireUserId();
        AiChartSpecVO spec = cmd != null ? cmd.getSpec() : null;
        if (spec == null || spec.getSeries() == null || spec.getSeries().isEmpty()) {
            throw new ServiceException("图表规格不能为空");
        }
        if (spec.getChartType() == null || !CHART_TYPES.contains(spec.getChartType())) {
            throw new ServiceException("图表类型不合法");
        }
        AiChartRecord record = new AiChartRecord();
        record.setUserId(userId);
        record.setQuestion(truncate(cmd.getQuestion(), 512));
        record.setTitle(truncate(spec.getTitle(), 128));
        record.setChartType(spec.getChartType());
        try {
            record.setSpecJson(objectMapper.writeValueAsString(spec));
        } catch (Exception e) {
            log.warn("[PivotOS] AI 图表规格序列化失败: {}", e.getMessage());
            throw new ServiceException("图表规格保存失败");
        }
        // tenant_id NOT NULL：多租户未启用时 TenantContext 为空，自动填充不生效，显式兜底
        Long tenantId = TenantContext.get();
        record.setTenantId(tenantId == null ? 0L : tenantId);
        aiChartRecordMapper.insert(record);
        return record.getId();
    }

    /** 我的历史分页（按保存时间倒序） */
    public PageResult<AiChartHistoryVO> pageMine(int pageNum, int pageSize) {
        Long userId = requireUserId();
        Page<AiChartRecord> page = new Page<>(pageNum, Math.min(pageSize, 100));
        Page<AiChartRecord> result = aiChartRecordMapper.selectPage(page,
                new LambdaQueryWrapper<AiChartRecord>()
                        .eq(AiChartRecord::getUserId, userId)
                        .orderByDesc(AiChartRecord::getCreateTime));
        List<AiChartHistoryVO> list = result.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(list, result.getTotal(), pageNum, pageSize);
    }

    /** 删除（仅归属人可删） */
    public void delete(Long id) {
        Long userId = requireUserId();
        if (id == null) {
            throw new ServiceException("图表 ID 不能为空");
        }
        AiChartRecord record = aiChartRecordMapper.selectById(id);
        if (record == null || !userId.equals(record.getUserId())) {
            throw new ServiceException("图表不存在或无权删除");
        }
        aiChartRecordMapper.deleteById(id);
    }

    private Long requireUserId() {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            throw new ServiceException("未登录或登录已过期");
        }
        return userId;
    }

    private String truncate(String value, int max) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private AiChartHistoryVO toVO(AiChartRecord record) {
        AiChartHistoryVO vo = new AiChartHistoryVO();
        vo.setId(record.getId());
        vo.setQuestion(record.getQuestion());
        vo.setTitle(record.getTitle());
        vo.setChartType(record.getChartType());
        vo.setSpecJson(record.getSpecJson());
        vo.setCreateTime(record.getCreateTime());
        return vo;
    }
}
