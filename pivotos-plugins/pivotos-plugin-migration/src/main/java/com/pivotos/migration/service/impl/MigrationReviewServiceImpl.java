package com.pivotos.migration.service.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.migration.domain.entity.MigrationReview;
import com.pivotos.migration.mapper.MigrationReviewMapper;
import com.pivotos.migration.service.MigrationReviewService;
import org.springframework.stereotype.Service;

/**
 * 迁移人工评审记录 Service 实现。
 */
@Service
public class MigrationReviewServiceImpl extends ServiceImpl<MigrationReviewMapper, MigrationReview>
        implements MigrationReviewService {
}
