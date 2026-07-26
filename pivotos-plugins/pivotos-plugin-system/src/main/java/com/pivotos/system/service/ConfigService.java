package com.pivotos.system.service;

import com.baomidou.mybatisplus.spring.service.IService;
import com.pivotos.common.core.page.PageResult;
import com.pivotos.system.domain.dto.ConfigQuery;
import com.pivotos.system.domain.dto.ConfigSaveRequest;
import com.pivotos.system.domain.entity.SysConfig;
import com.pivotos.system.domain.vo.ConfigVO;

/** 参数配置服务（IService 接口若你统一改成 mybatis-plus-spring 包，这里同步改） */
public interface ConfigService extends IService<SysConfig> {

    /** 分页查询参数 */
    PageResult<ConfigVO> pageConfigs(ConfigQuery query);

    /** 查询参数详情 */
    ConfigVO getConfig(Long configId);

    /** 新增参数，返回参数ID */
    Long createConfig(ConfigSaveRequest request);

    /** 修改参数 */
    void updateConfig(ConfigSaveRequest request);

    /** 删除参数（内置参数拒绝删除） */
    void deleteConfig(Long configId);

    /** 按键名读取参数值，缺失返回默认值 */
    String getConfigValue(String configKey, String defaultValue);
}
