package com.pivotos.ai.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.ai.domain.entity.AiApiKey;
import com.pivotos.ai.domain.vo.AiUsageProviderVO;
import com.pivotos.ai.domain.vo.AiUsageSummaryVO;
import com.pivotos.ai.domain.vo.AiUsageUserVO;
import com.pivotos.ai.mapper.AiApiKeyMapper;
import com.pivotos.ai.mapper.AiUsageMapper;
import com.pivotos.ai.service.AiUsageService;
import com.pivotos.system.api.dto.UserDTO;
import com.pivotos.system.api.facade.IUserFacade;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * AI Token 用量统计服务实现（S92）
 *
 * <p>聚合全部下推 SQL（GROUP BY），服务层只做类型归一与展示字段回填
 * （Key 备注名 / 用户名走门面契约，缺省静默置空不阻断看板）。
 */
@Service
@RequiredArgsConstructor
public class AiUsageServiceImpl implements AiUsageService {

    /** 统计窗口上限（防全表扫描） */
    private static final int MAX_DAYS = 90;

    private final AiUsageMapper usageMapper;
    private final AiApiKeyMapper apiKeyMapper;
    /** 用户门面（可选依赖：system 插件不在时装载不失败，用户名留空） */
    private final ObjectProvider<IUserFacade> userFacadeProvider;

    @Override
    public AiUsageSummaryVO summary(int days) {
        LocalDateTime start = startOf(clamp(days));
        AiUsageSummaryVO vo = new AiUsageSummaryVO();
        Map<String, Object> summary = usageMapper.selectSummary(start);
        vo.setCalls(longOf(summary, "calls"));
        vo.setFailedCalls(longOf(summary, "failedCalls"));
        vo.setPromptTokens(longOf(summary, "promptTokens"));
        vo.setCompletionTokens(longOf(summary, "completionTokens"));
        vo.setTotalTokens(longOf(summary, "totalTokens"));

        vo.setByScene(usageMapper.selectByScene(start).stream().map(row -> {
            AiUsageSummaryVO.SceneItem item = new AiUsageSummaryVO.SceneItem();
            item.setScene(strOf(row, "scene"));
            item.setCalls(longOf(row, "calls"));
            item.setTotalTokens(longOf(row, "totalTokens"));
            return item;
        }).toList());

        // 日趋势：SQL 按日聚合，缺日补 0（与 chatStats 口径一致）
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        LocalDate today = LocalDate.now();
        for (int i = clamp(days) - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i).toString(), null);
        }
        for (Map<String, Object> row : usageMapper.selectTrend(start)) {
            byDay.put(strOf(row, "day"), row);
        }
        List<AiUsageSummaryVO.TrendItem> trend = new ArrayList<>();
        byDay.forEach((day, row) -> {
            AiUsageSummaryVO.TrendItem item = new AiUsageSummaryVO.TrendItem();
            item.setDay(day);
            item.setCalls(row == null ? 0L : longOf(row, "calls"));
            item.setTotalTokens(row == null ? 0L : longOf(row, "totalTokens"));
            trend.add(item);
        });
        vo.setTrend(trend);
        return vo;
    }

    @Override
    public List<AiUsageProviderVO> byProvider(int days) {
        LocalDateTime start = startOf(clamp(days));
        List<AiUsageProviderVO> list = usageMapper.selectByProvider(start).stream().map(row -> {
            AiUsageProviderVO vo = new AiUsageProviderVO();
            vo.setProviderCode(strOf(row, "providerCode"));
            vo.setKeyId(longOfNullable(row, "keyId"));
            vo.setCalls(longOf(row, "calls"));
            vo.setPromptTokens(longOf(row, "promptTokens"));
            vo.setCompletionTokens(longOf(row, "completionTokens"));
            vo.setTotalTokens(longOf(row, "totalTokens"));
            return vo;
        }).toList();

        // Key 备注名回填（查不到的留空，静态兜底无 Key 跳过）
        List<Long> keyIds = list.stream().map(AiUsageProviderVO::getKeyId)
                .filter(Objects::nonNull).distinct().toList();
        if (!keyIds.isEmpty()) {
            Map<Long, String> labels = apiKeyMapper.selectList(Wrappers.<AiApiKey>lambdaQuery()
                            .select(AiApiKey::getId, AiApiKey::getLabel)
                            .in(AiApiKey::getId, keyIds))
                    .stream().collect(Collectors.toMap(AiApiKey::getId, AiApiKey::getLabel, (a, b) -> a));
            list.forEach(vo -> vo.setKeyLabel(vo.getKeyId() == null ? null : labels.get(vo.getKeyId())));
        }
        return list;
    }

    @Override
    public List<AiUsageUserVO> byUser(int days) {
        LocalDateTime start = startOf(clamp(days));
        List<AiUsageUserVO> list = usageMapper.selectByUser(start).stream().map(row -> {
            AiUsageUserVO vo = new AiUsageUserVO();
            vo.setUserId(longOfNullable(row, "userId"));
            vo.setCalls(longOf(row, "calls"));
            vo.setTotalTokens(longOf(row, "totalTokens"));
            return vo;
        }).toList();

        // 用户名/昵称回填（门面缺失或用户已删时留空）
        IUserFacade userFacade = userFacadeProvider.getIfAvailable();
        List<Long> userIds = list.stream().map(AiUsageUserVO::getUserId)
                .filter(Objects::nonNull).distinct().toList();
        if (userFacade != null && !userIds.isEmpty()) {
            Map<Long, UserDTO> users = userFacade.listByIds(userIds).stream()
                    .collect(Collectors.toMap(UserDTO::getId, Function.identity(), (a, b) -> a));
            list.forEach(vo -> {
                UserDTO user = vo.getUserId() == null ? null : users.get(vo.getUserId());
                if (user != null) {
                    vo.setUsername(user.getUsername());
                    vo.setNickname(user.getNickname());
                }
            });
        }
        return list;
    }

    // ---------- 内部工具 ----------

    private int clamp(int days) {
        return Math.min(Math.max(days, 1), MAX_DAYS);
    }

    private LocalDateTime startOf(int days) {
        return LocalDate.now().minusDays(days - 1L).atStartOfDay();
    }

    /** COUNT/SUM 结果归一为 long（MySQL 驱动可能返回 Long/BigDecimal） */
    private long longOf(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /** 可空列（key_id/user_id）归一为 Long */
    private Long longOfNullable(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value instanceof Number number ? number.longValue() : null;
    }

    private String strOf(Map<String, Object> row, String key) {
        Object value = row == null ? null : row.get(key);
        return value == null ? null : value.toString();
    }
}
