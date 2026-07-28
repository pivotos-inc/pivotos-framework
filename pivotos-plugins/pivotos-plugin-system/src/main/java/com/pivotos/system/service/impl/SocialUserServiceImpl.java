package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.domain.entity.SysSocialUser;
import com.pivotos.system.mapper.SysSocialUserMapper;
import com.pivotos.system.service.SocialUserService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

/** 三方社交账号绑定服务实现 */
@Service
public class SocialUserServiceImpl extends ServiceImpl<SysSocialUserMapper, SysSocialUser>
        implements SocialUserService {

    @Override
    public SysSocialUser findByChannelOpenId(String channel, String openId) {
        return getOne(Wrappers.<SysSocialUser>lambdaQuery()
                .eq(SysSocialUser::getChannel, channel)
                .eq(SysSocialUser::getOpenId, openId)
                .last("LIMIT 1"));
    }

    @Override
    public void bind(Long userId, String channel, String openId, String unionId) {
        SysSocialUser entity = new SysSocialUser();
        entity.setUserId(userId);
        entity.setChannel(channel);
        entity.setOpenId(openId);
        entity.setUnionId(unionId);
        try {
            save(entity);
        } catch (DuplicateKeyException e) {
            // uk_channel_openid 命中：并发或重复绑定的统一出口
            throw new ServiceException(SystemErrorCode.SOCIAL_ALREADY_BOUND);
        }
    }
}
