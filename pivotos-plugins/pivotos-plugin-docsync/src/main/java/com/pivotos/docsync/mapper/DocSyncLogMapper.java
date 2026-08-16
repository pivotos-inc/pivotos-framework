package com.pivotos.docsync.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.docsync.entity.DocSyncLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocSyncLogMapper extends BaseMapper<DocSyncLog> {
}
