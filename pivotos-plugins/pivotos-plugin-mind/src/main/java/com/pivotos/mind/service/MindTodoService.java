package com.pivotos.mind.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.mind.api.dto.AiTodoBody;
import com.pivotos.mind.api.dto.TodoQuery;
import com.pivotos.mind.api.dto.TodoSaveBody;
import com.pivotos.mind.domain.entity.MindTodo;
import com.pivotos.mind.api.vo.TodoVO;

import java.util.List;

/** 智域待办服务 */
public interface MindTodoService extends IService<MindTodo> {

    /** 分页列表 */
    PageResult<TodoVO> pageTodo(Long userId, TodoQuery query);

    /** 最近待办（首页用） */
    List<TodoVO> recentTodo(Long userId, int limit);

    /** 创建 */
    Long createTodo(Long userId, TodoSaveBody body);

    /** 修改 */
    void updateTodo(Long userId, Long id, TodoSaveBody body);

    /** 切换完成状态 */
    void toggleTodo(Long userId, Long id);

    /** 删除 */
    void deleteTodo(Long userId, Long id);

    /** AI 智能创建 */
    List<TodoVO> aiCreateTodo(Long userId, AiTodoBody body);

    /** 统计 */
    Long countByUser(Long userId, Integer status);
}
