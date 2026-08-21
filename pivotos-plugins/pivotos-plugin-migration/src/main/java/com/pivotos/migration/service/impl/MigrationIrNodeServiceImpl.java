package com.pivotos.migration.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.migration.domain.entity.MigrationIrNode;
import com.pivotos.migration.mapper.MigrationIrNodeMapper;
import com.pivotos.migration.service.MigrationIrNodeService;
import org.springframework.stereotype.Service;

/**
 * 迁移 IR 节点 Service 实现。
 */
@Service
public class MigrationIrNodeServiceImpl extends ServiceImpl<MigrationIrNodeMapper, MigrationIrNode>
        implements MigrationIrNodeService {
}
