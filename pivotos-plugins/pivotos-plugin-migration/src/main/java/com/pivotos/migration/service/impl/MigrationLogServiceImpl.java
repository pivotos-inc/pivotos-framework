package com.pivotos.migration.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.migration.domain.entity.MigrationLog;
import com.pivotos.migration.mapper.MigrationLogMapper;
import com.pivotos.migration.service.MigrationLogService;
import org.springframework.stereotype.Service;

/**
 * 迁移执行日志 Service 实现。
 */
@Service
public class MigrationLogServiceImpl extends ServiceImpl<MigrationLogMapper, MigrationLog>
        implements MigrationLogService {
}
