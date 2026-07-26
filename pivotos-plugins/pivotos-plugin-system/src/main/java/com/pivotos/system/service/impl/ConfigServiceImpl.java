package com.pivotos.system.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.pivotos.common.core.exception.ServiceException;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.api.enums.SystemErrorCode;
import com.pivotos.system.convert.ConfigConvert;
import com.pivotos.system.domain.dto.ConfigQuery;
import com.pivotos.system.domain.dto.ConfigSaveRequest;
import com.pivotos.system.domain.entity.SysConfig;
import com.pivotos.system.domain.vo.ConfigVO;
import com.pivotos.system.mapper.SysConfigMapper;
import com.pivotos.system.service.ConfigService;
import com.pivotos.system.support.PageUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 参数配置服务实现 */
@Service
@RequiredArgsConstructor
public class ConfigServiceImpl extends ServiceImpl<SysConfigMapper, SysConfig> implements ConfigService {

    private static final String BUILTIN_YES = "Y";

    private final ConfigConvert configConvert;

    @Override
    public PageResult<ConfigVO> pageConfigs(ConfigQuery query) {
        Page<SysConfig> page = page(PageUtils.toMpPage(query), Wrappers.<SysConfig>lambdaQuery()
                .like(StringUtils.hasText(query.getConfigName()), SysConfig::getConfigName, query.getConfigName())
                .like(StringUtils.hasText(query.getConfigKey()), SysConfig::getConfigKey, query.getConfigKey())
                .eq(StringUtils.hasText(query.getConfigType()), SysConfig::getConfigType, query.getConfigType())
                .orderByAsc(SysConfig::getId));
        return PageUtils.toPageResult(page, configConvert.toVoList(page.getRecords()));
    }

    @Override
    public ConfigVO getConfig(Long configId) {
        return configConvert.toVo(requireConfig(configId));
    }

    @Override
    public Long createConfig(ConfigSaveRequest request) {
        checkKeyUnique(request.getConfigKey(), null);
        SysConfig entity = configConvert.toEntity(request);
        entity.setId(null);
        save(entity);
        return entity.getId();
    }

    @Override
    public void updateConfig(ConfigSaveRequest request) {
        requireConfig(request.getId());
        checkKeyUnique(request.getConfigKey(), request.getId());
        updateById(configConvert.toEntity(request));
    }

    @Override
    public void deleteConfig(Long configId) {
        SysConfig config = requireConfig(configId);
        if (BUILTIN_YES.equals(config.getConfigType())) {
            throw new ServiceException(SystemErrorCode.CONFIG_BUILTIN_FORBIDDEN);
        }
        removeById(configId);
    }

    @Override
    public String getConfigValue(String configKey, String defaultValue) {
        SysConfig config = getOne(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getConfigKey, configKey));
        return config != null ? config.getConfigValue() : defaultValue;
    }

    private SysConfig requireConfig(Long configId) {
        SysConfig config = getById(configId);
        if (config == null) {
            throw new ServiceException(SystemErrorCode.CONFIG_NOT_FOUND);
        }
        return config;
    }

    private void checkKeyUnique(String configKey, Long excludeId) {
        long count = count(Wrappers.<SysConfig>lambdaQuery()
                .eq(SysConfig::getConfigKey, configKey)
                .ne(excludeId != null, SysConfig::getId, excludeId));
        if (count > 0) {
            throw new ServiceException(SystemErrorCode.CONFIG_KEY_EXISTS);
        }
    }
}
