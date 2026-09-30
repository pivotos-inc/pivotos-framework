package com.pivotos.mind.controller;

import cn.dev33.satoken.annotation.SaCheckLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.common.core.result.R;
import com.pivotos.mind.api.dto.AiTodoBody;
import com.pivotos.mind.api.dto.TodoQuery;
import com.pivotos.mind.api.dto.TodoSaveBody;
import com.pivotos.mind.api.vo.MindStatsVO;
import com.pivotos.mind.api.vo.TodoVO;
import com.pivotos.mind.service.MindKnowledgeService;
import com.pivotos.mind.service.MindTodoService;
import com.pivotos.starter.auth.account.StpMindUtil;
import com.pivotos.starter.core.context.LoginContext;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 智域待办接口
 */
@Tag(name = "智域待办", description = "PivotOS·智域智能待办")
@RestController
@RequestMapping("/mind/todo")
@RequiredArgsConstructor
@SaCheckLogin(type = StpMindUtil.TYPE)
public class MindTodoController {

    private final MindTodoService todoService;
    private final MindKnowledgeService knowledgeService;

    @Operation(summary = "分页列表")
    @GetMapping("/list")
    public R<PageResult<TodoVO>> list(TodoQuery query) {
        return R.ok(todoService.pageTodo(requireUserId(), query));
    }

    @Operation(summary = "最近待办")
    @GetMapping("/recent")
    public R<List<TodoVO>> recent(@RequestParam(defaultValue = "5") int limit) {
        return R.ok(todoService.recentTodo(requireUserId(), limit));
    }

    @Operation(summary = "创建")
    @PostMapping
    public R<Long> create(@Validated @RequestBody TodoSaveBody body) {
        return R.ok(todoService.createTodo(requireUserId(), body));
    }

    @Operation(summary = "修改")
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Validated @RequestBody TodoSaveBody body) {
        todoService.updateTodo(requireUserId(), id, body);
        return R.ok();
    }

    @Operation(summary = "切换完成状态")
    @PutMapping("/{id}/status")
    public R<Void> toggle(@PathVariable Long id) {
        todoService.toggleTodo(requireUserId(), id);
        return R.ok();
    }

    @Operation(summary = "删除")
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        todoService.deleteTodo(requireUserId(), id);
        return R.ok();
    }

    @Operation(summary = "AI 智能创建")
    @PostMapping("/ai-create")
    public R<List<TodoVO>> aiCreate(@Validated @RequestBody AiTodoBody body) {
        return R.ok(todoService.aiCreateTodo(requireUserId(), body));
    }

    @Operation(summary = "首页统计")
    @GetMapping("/stats")
    public R<MindStatsVO> stats() {
        Long userId = requireUserId();
        MindStatsVO vo = new MindStatsVO();
        vo.setKnowledgeCount(knowledgeService.countByUser(userId));
        vo.setTodoTotal(todoService.countByUser(userId, null));
        vo.setTodoPending(todoService.countByUser(userId, 0));
        return R.ok(vo);
    }

    private Long requireUserId() {
        Long userId = LoginContext.getUserId();
        if (userId == null) {
            throw new IllegalStateException("登录态异常");
        }
        return userId;
    }
}
