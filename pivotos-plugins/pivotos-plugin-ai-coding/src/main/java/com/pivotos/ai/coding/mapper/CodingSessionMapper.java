package com.pivotos.ai.coding.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.ai.coding.domain.entity.CodingSession;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI Coding session mapper.
 *
 * @author PivotOS
 * @since 2.2.0
 */
@Mapper
public interface CodingSessionMapper extends BaseMapper<CodingSession> {
}
