package com.pivotos.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.pivotos.common.core.result.R;
import com.pivotos.starter.auth.account.StpSysUtil;
import com.pivotos.system.domain.dto.PostQuery;
import com.pivotos.system.domain.dto.PostSaveRequest;
import com.pivotos.system.domain.vo.PostVO;
import com.pivotos.system.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 岗位管理 */
@RestController
@RequestMapping("/system/post")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    /** 岗位列表 */
    @GetMapping("/list")
    @SaCheckPermission(value = "system:post:query", type = StpSysUtil.TYPE)
    public R<List<PostVO>> list(PostQuery query) {
        return R.ok(postService.listPosts(query));
    }

    /** 详情 */
    @GetMapping("/{id}")
    @SaCheckPermission(value = "system:post:query", type = StpSysUtil.TYPE)
    public R<PostVO> get(@PathVariable Long id) {
        return R.ok(postService.getPost(id));
    }

    /** 新增 */
    @PostMapping
    @SaCheckPermission(value = "system:post:add", type = StpSysUtil.TYPE)
    public R<Long> create(@Validated @RequestBody PostSaveRequest request) {
        return R.ok(postService.createPost(request));
    }

    /** 修改 */
    @PutMapping
    @SaCheckPermission(value = "system:post:edit", type = StpSysUtil.TYPE)
    public R<Void> update(@Validated @RequestBody PostSaveRequest request) {
        postService.updatePost(request);
        return R.ok();
    }

    /** 删除 */
    @DeleteMapping("/{id}")
    @SaCheckPermission(value = "system:post:remove", type = StpSysUtil.TYPE)
    public R<Void> delete(@PathVariable Long id) {
        postService.deletePost(id);
        return R.ok();
    }
}
