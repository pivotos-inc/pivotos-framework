package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.convert.NoticeConvert;
import com.pivotos.system.domain.dto.NoticeQuery;
import com.pivotos.system.domain.dto.NoticeSaveRequest;
import com.pivotos.system.domain.entity.SysNotice;
import com.pivotos.system.domain.vo.NoticeVO;
import com.pivotos.system.mapper.SysNoticeMapper;
import com.pivotos.system.service.NoticeService;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

/** 通知公告实现（状态机：0草稿 → 1已发布 ⇄ 2已撤回） */
@Service
@RequiredArgsConstructor
public class NoticeServiceImpl extends ServiceImpl<SysNoticeMapper, SysNotice>
        implements NoticeService {

    /** 状态：草稿 */
    private static final int STATUS_DRAFT = 0;

    /** 状态：已发布 */
    private static final int STATUS_PUBLISHED = 1;

    /** 状态：已撤回 */
    private static final int STATUS_REVOKED = 2;

    private final NoticeConvert noticeConvert;

    @Override
    public PageResult<NoticeVO> pageNotices(NoticeQuery query) {
        Page<SysNotice> page = page(PageUtils.toMpPage(query), Wrappers.<SysNotice>lambdaQuery()
                .like(StringUtils.hasText(query.getTitle()), SysNotice::getTitle, query.getTitle())
                .eq(query.getNoticeType() != null, SysNotice::getNoticeType, query.getNoticeType())
                .eq(query.getStatus() != null, SysNotice::getStatus, query.getStatus())
                // 列表不回吐富文本大字段，详情接口单独取
                .select(SysNotice.class, info -> !"content".equals(info.getProperty()))
                .orderByDesc(SysNotice::getUpdateTime));
        return PageUtils.toPageResult(page, noticeConvert.toVoList(page.getRecords()));
    }

    @Override
    public NoticeVO getNotice(Long id) {
        return noticeConvert.toVo(requireNotice(id));
    }

    @Override
    public Long createNotice(NoticeSaveRequest request) {
        SysNotice entity = noticeConvert.toEntity(request);
        entity.setId(null);
        entity.setStatus(STATUS_DRAFT);
        save(entity);
        return entity.getId();
    }

    @Override
    public void updateNotice(NoticeSaveRequest request) {
        SysNotice exist = requireNotice(request.getId());
        if (exist.getStatus() == STATUS_PUBLISHED) {
            throw new ServiceException(SystemErrorCode.NOTICE_PUBLISHED_READONLY);
        }
        updateById(noticeConvert.toEntity(request));
    }

    @Override
    public void deleteNotice(Long id) {
        requireNotice(id);
        removeById(id);
    }

    @Override
    public void publishNotice(Long id) {
        SysNotice notice = requireNotice(id);
        if (notice.getStatus() == STATUS_PUBLISHED) {
            throw new ServiceException(SystemErrorCode.NOTICE_STATUS_INVALID);
        }
        SysNotice update = new SysNotice();
        update.setId(id);
        update.setStatus(STATUS_PUBLISHED);
        update.setPublishTime(LocalDateTime.now());
        updateById(update);
    }

    @Override
    public void revokeNotice(Long id) {
        SysNotice notice = requireNotice(id);
        if (notice.getStatus() != STATUS_PUBLISHED) {
            throw new ServiceException(SystemErrorCode.NOTICE_STATUS_INVALID);
        }
        SysNotice update = new SysNotice();
        update.setId(id);
        update.setStatus(STATUS_REVOKED);
        updateById(update);
    }

    @Override
    public List<NoticeVO> listPublished(int limit) {
        List<SysNotice> list = list(Wrappers.<SysNotice>lambdaQuery()
                .eq(SysNotice::getStatus, STATUS_PUBLISHED)
                .select(SysNotice.class, info -> !"content".equals(info.getProperty()))
                .orderByDesc(SysNotice::getPublishTime)
                .last("LIMIT " + Math.clamp(limit, 1, 50)));
        return noticeConvert.toVoList(list);
    }

    @Override
    public NoticeVO getPublished(Long id) {
        SysNotice notice = getById(id);
        if (notice == null || notice.getStatus() != STATUS_PUBLISHED) {
            throw new ServiceException(SystemErrorCode.NOTICE_NOT_FOUND);
        }
        return noticeConvert.toVo(notice);
    }

    private SysNotice requireNotice(Long id) {
        SysNotice notice = getById(id);
        if (notice == null) {
            throw new ServiceException(SystemErrorCode.NOTICE_NOT_FOUND);
        }
        return notice;
    }
}
