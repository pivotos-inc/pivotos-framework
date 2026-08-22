package com.pivotos.migration.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.migration.domain.entity.MigrationReview;
import org.apache.ibatis.annotations.Mapper;

/**
 * 迁移人工评审记录 Mapper。
 */
@Mapper
public interface MigrationReviewMapper extends BaseMapper<MigrationReview> {
}
