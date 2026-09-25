package com.pivotos.mind.service.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.mind.api.dto.AiTodoBody;
import com.pivotos.mind.api.dto.TodoQuery;
import com.pivotos.mind.api.dto.TodoSaveBody;
import com.pivotos.mind.api.enums.MindErrorCode;
import com.pivotos.mind.domain.entity.MindTodo;
import com.pivotos.mind.api.vo.TodoVO;
import com.pivotos.mind.mapper.MindTodoMapper;
import com.pivotos.mind.service.MindTodoService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 智域待办服务实现 */
@Service
@RequiredArgsConstructor
public class MindTodoServiceImpl extends ServiceImpl<MindTodoMapper, MindTodo>
        implements MindTodoService {

    private static final Logger log = LoggerFactory.getLogger(MindTodoServiceImpl.class);

    private final ChatClient chatClient;

    @Override
    public PageResult<TodoVO> pageTodo(Long userId, TodoQuery query) {
        Page<MindTodo> page = page(new Page<>(query.getPageNum(), query.getPageSize()),
                Wrappers.<MindTodo>lambdaQuery()
                        .eq(MindTodo::getUserId, userId)
                        .eq(query.getStatus() != null, MindTodo::getStatus, query.getStatus())
                        .orderByDesc(MindTodo::getCreateTime));
        List<TodoVO> rows = page.getRecords().stream().map(this::toVo).toList();
        return new PageResult<>(rows, page.getTotal(), (int) page.getCurrent(), (int) page.getSize());
    }

    @Override
    public List<TodoVO> recentTodo(Long userId, int limit) {
        return list(Wrappers.<MindTodo>lambdaQuery()
                .eq(MindTodo::getUserId, userId)
                .orderByDesc(MindTodo::getCreateTime)
                .last("LIMIT " + Math.min(limit, 50)))
                .stream().map(this::toVo).toList();
    }

    @Override
    public Long createTodo(Long userId, TodoSaveBody body) {
        MindTodo entity = new MindTodo();
        entity.setUserId(userId);
        entity.setTitle(body.getTitle());
        entity.setRemark(body.getRemark());
        entity.setPriority(body.getPriority());
        entity.setDueTime(body.getDueTime());
        entity.setStatus(0);
        save(entity);
        return entity.getId();
    }

    @Override
    public void updateTodo(Long userId, Long id, TodoSaveBody body) {
        MindTodo entity = getById(id);
        if (entity == null || entity.getDeleted() == 1 || !Objects.equals(entity.getUserId(), userId)) {
            throw new ServiceException(MindErrorCode.TODO_NOT_FOUND);
        }
        entity.setTitle(body.getTitle());
        entity.setRemark(body.getRemark());
        entity.setPriority(body.getPriority());
        entity.setDueTime(body.getDueTime());
        updateById(entity);
    }

    @Override
    public void toggleTodo(Long userId, Long id) {
        MindTodo entity = getById(id);
        if (entity == null || entity.getDeleted() == 1 || !Objects.equals(entity.getUserId(), userId)) {
            throw new ServiceException(MindErrorCode.TODO_NOT_FOUND);
        }
        int newStatus = Objects.equals(entity.getStatus(), 1) ? 0 : 1;
        entity.setStatus(newStatus);
        entity.setFinishTime(newStatus == 1 ? LocalDateTime.now() : null);
        updateById(entity);
    }

    @Override
    public void deleteTodo(Long userId, Long id) {
        MindTodo entity = getById(id);
        if (entity == null || entity.getDeleted() == 1 || !Objects.equals(entity.getUserId(), userId)) {
            throw new ServiceException(MindErrorCode.TODO_NOT_FOUND);
        }
        removeById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public List<TodoVO> aiCreateTodo(Long userId, AiTodoBody body) {
        String json = chatClient.prompt()
                .system("你是一名任务拆解助手。请把用户的自然语言描述拆分为具体的待办事项，返回 JSON 数组。" +
                        "每个元素包含 title（标题，必填）、remark（备注，可空）、priority（优先级 low/medium/high，默认 medium）、" +
                        "dueTime（计划完成时间，ISO-8601 格式如 2026-08-28T10:00:00，无则 null）。只返回 JSON 数组，不要额外说明。")
                .user(body.getDescription())
                .call()
                .content();
        List<MindTodo> todos = parseAiTodos(userId, json);
        if (todos.isEmpty()) {
            throw new ServiceException(MindErrorCode.TODO_AI_PARSE_FAILED);
        }
        saveBatch(todos);
        return todos.stream().map(this::toVo).toList();
    }

    @Override
    public Long countByUser(Long userId, Integer status) {
        return count(Wrappers.<MindTodo>lambdaQuery()
                .eq(MindTodo::getUserId, userId)
                .eq(status != null, MindTodo::getStatus, status));
    }

    private List<MindTodo> parseAiTodos(Long userId, String text) {
        List<MindTodo> result = new ArrayList<>();
        try {
            String clean = text.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```(?:json)?\\s*", "").replaceAll("\\s*```$", "");
            }
            JSONArray array = JSON.parseArray(clean);
            for (int i = 0; i < array.size(); i++) {
                JSONObject obj = array.getJSONObject(i);
                MindTodo todo = new MindTodo();
                todo.setUserId(userId);
                todo.setTitle(obj.getString("title"));
                todo.setRemark(obj.getString("remark"));
                todo.setPriority(obj.getString("priority"));
                todo.setDueTime(obj.getLocalDateTime("dueTime", null));
                todo.setStatus(0);
                if (todo.getTitle() != null && !todo.getTitle().isBlank()) {
                    result.add(todo);
                }
            }
        } catch (Exception e) {
            log.warn("[PivotOS Mind] AI 待办解析失败：{}", text, e);
        }
        return result;
    }

    private TodoVO toVo(MindTodo entity) {
        TodoVO vo = new TodoVO();
        vo.setId(entity.getId());
        vo.setTitle(entity.getTitle());
        vo.setRemark(entity.getRemark());
        vo.setPriority(entity.getPriority());
        vo.setStatus(entity.getStatus());
        vo.setDueTime(entity.getDueTime());
        vo.setFinishTime(entity.getFinishTime());
        vo.setCreateTime(entity.getCreateTime());
        return vo;
    }
}
