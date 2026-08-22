package com.pivotos.docsync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.docsync.entity.DocSyncConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocSyncConfigMapper extends BaseMapper<DocSyncConfig> {
}
