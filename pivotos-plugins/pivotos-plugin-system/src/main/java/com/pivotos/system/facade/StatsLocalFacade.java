package com.pivotos.system.facade;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.pivotos.system.api.dto.SystemStatsDTO;
import com.pivotos.system.api.dto.TrendPointDTO;
import com.pivotos.system.api.facade.IStatsFacade;
import com.pivotos.system.domain.entity.SysLoginLog;
import com.pivotos.system.mapper.SysLoginLogMapper;
import com.pivotos.system.service.DeptService;
import com.pivotos.system.service.PostService;
import com.pivotos.system.service.RoleService;
import com.pivotos.system.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 系统运营统计门面本地实现（S71：运营工作台/数据大屏聚合数据源） */
@Component
@RequiredArgsConstructor
public class StatsLocalFacade implements IStatsFacade {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final UserService userService;
    private final RoleService roleService;
    private final DeptService deptService;
    private final PostService postService;
    private final SysLoginLogMapper loginLogMapper;

    @Override
    public SystemStatsDTO systemStats() {
        SystemStatsDTO dto = new SystemStatsDTO();
        dto.setUserCount(userService.count());
        dto.setRoleCount(roleService.count());
        dto.setDeptCount(deptService.count());
        dto.setPostCount(postService.count());
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        dto.setTodayLogins(loginLogMapper.selectCount(Wrappers.<SysLoginLog>lambdaQuery()
                .eq(SysLoginLog::getStatus, 0)
                .ge(SysLoginLog::getLoginTime, dayStart)));
        return dto;
    }

    @Override
    public List<TrendPointDTO> loginTrend(int days) {
        if (days <= 0) {
            return List.of();
        }
        LocalDate today = LocalDate.now();
        LocalDateTime start = today.minusDays(days - 1L).atStartOfDay();
        // 缺日补 0：先按日期预置有序 map，再聚合查询结果回填
        Map<String, Long> byDay = new LinkedHashMap<>();
        for (int i = days - 1; i >= 0; i--) {
            byDay.put(today.minusDays(i).format(DAY_FMT), 0L);
        }
        List<SysLoginLog> logs = loginLogMapper.selectList(Wrappers.<SysLoginLog>lambdaQuery()
                .select(SysLoginLog::getLoginTime)
                .eq(SysLoginLog::getStatus, 0)
                .ge(SysLoginLog::getLoginTime, start));
        for (SysLoginLog log : logs) {
            String day = log.getLoginTime().toLocalDate().format(DAY_FMT);
            byDay.computeIfPresent(day, (k, v) -> v + 1);
        }
        List<TrendPointDTO> result = new ArrayList<>(byDay.size());
        byDay.forEach((date, value) -> result.add(new TrendPointDTO(date, value)));
        return result;
    }
}
