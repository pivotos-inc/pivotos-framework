package com.pivotos.system.service;

import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.NoticeQuery;
import com.pivotos.system.domain.dto.NoticeSaveRequest;
import com.pivotos.system.domain.vo.NoticeVO;

import java.util.List;

/** 通知公告服务（2.1-F2） */
public interface NoticeService {

    /** 分页查询（管理端） */
    PageResult<NoticeVO> pageNotices(NoticeQuery query);

    /** 详情（管理端） */
    NoticeVO getNotice(Long id);

    /** 新增（草稿态） */
    Long createNotice(NoticeSaveRequest request);

    /** 修改（已发布不允许编辑，需先撤回） */
    void updateNotice(NoticeSaveRequest request);

    /** 删除 */
    void deleteNotice(Long id);

    /** 发布（草稿/已撤回 → 已发布，刷新发布时间） */
    void publishNotice(Long id);

    /** 撤回（已发布 → 已撤回） */
    void revokeNotice(Long id);

    /** 最新已发布公告（三端公告栏/首页卡片，按发布时间倒序） */
    List<NoticeVO> listPublished(int limit);

    /** 已发布公告详情（登录即可读，非已发布态报不存在） */
    NoticeVO getPublished(Long id);
}
