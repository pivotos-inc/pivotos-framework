package com.pivotos.file.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.file.domain.entity.SysFile;
import org.apache.ibatis.annotations.Mapper;

/** 文件元数据 Mapper */
@Mapper
public interface SysFileMapper extends BaseMapper<SysFile> {
}
