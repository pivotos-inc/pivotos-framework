package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.convert.PostConvert;
import com.pivotos.system.domain.dto.PostQuery;
import com.pivotos.system.domain.dto.PostSaveRequest;
import com.pivotos.system.domain.entity.SysPost;
import com.pivotos.system.domain.entity.SysUser;
import com.pivotos.system.domain.vo.PostVO;
import com.pivotos.system.mapper.SysPostMapper;
import com.pivotos.system.mapper.SysUserMapper;
import com.pivotos.system.service.PostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/** 岗位服务实现 */
@Service
@RequiredArgsConstructor
public class PostServiceImpl extends ServiceImpl<SysPostMapper, SysPost> implements PostService {

    private final PostConvert postConvert;
    private final SysUserMapper userMapper;

    @Override
    public List<PostVO> listPosts(PostQuery query) {
        List<SysPost> posts = list(Wrappers.<SysPost>lambdaQuery()
                .like(StringUtils.hasText(query.getPostCode()), SysPost::getPostCode, query.getPostCode())
                .like(StringUtils.hasText(query.getPostName()), SysPost::getPostName, query.getPostName())
                .eq(query.getStatus() != null, SysPost::getStatus, query.getStatus())
                .orderByAsc(SysPost::getSort));
        return postConvert.toVoList(posts);
    }

    @Override
    public PostVO getPost(Long postId) {
        return postConvert.toVo(requirePost(postId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long createPost(PostSaveRequest request) {
        // 岗位编码唯一性校验
        checkPostCodeUnique(request.getPostCode(), null);
        SysPost entity = postConvert.toEntity(request);
        entity.setId(null);
        entity.setSort(request.getSort() != null ? request.getSort() : 0);
        entity.setStatus(request.getStatus() != null ? request.getStatus() : 0);
        save(entity);
        return entity.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updatePost(PostSaveRequest request) {
        SysPost exist = requirePost(request.getId());
        // 岗位编码唯一性校验
        if (!exist.getPostCode().equals(request.getPostCode())) {
            checkPostCodeUnique(request.getPostCode(), request.getId());
        }
        SysPost entity = postConvert.toEntity(request);
        updateById(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long postId) {
        requirePost(postId);
        long users = userMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery().eq(SysUser::getPostId, postId));
        if (users > 0) {
            throw new ServiceException(SystemErrorCode.POST_HAS_USERS);
        }
        removeById(postId);
    }

    /** 获取岗位，不存在则抛异常 */
    private SysPost requirePost(Long postId) {
        SysPost post = getById(postId);
        if (post == null) {
            throw new ServiceException(SystemErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    /** 校验岗位编码唯一 */
    private void checkPostCodeUnique(String postCode, Long excludeId) {
        Long count = lambdaQuery()
                .eq(SysPost::getPostCode, postCode)
                .ne(excludeId != null, SysPost::getId, excludeId)
                .count();
        if (count > 0) {
            throw new ServiceException(SystemErrorCode.POST_CODE_EXISTS);
        }
    }
}
