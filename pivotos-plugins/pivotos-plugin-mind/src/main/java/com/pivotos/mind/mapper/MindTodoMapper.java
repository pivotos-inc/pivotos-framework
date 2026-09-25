package com.pivotos.mind.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.pivotos.mind.domain.entity.MindTodo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MindTodoMapper extends BaseMapper<MindTodo> {
}
