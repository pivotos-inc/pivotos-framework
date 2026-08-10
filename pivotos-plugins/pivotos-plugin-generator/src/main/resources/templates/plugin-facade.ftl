package com.pivotos.${pluginName}.api.facade;

/**
 * ${displayName}插件对外契约（跨 Plugin 调用唯一入口）。
 * <p>
 * 契约先行占位：在此声明对外服务能力与 DTO，其他插件只许依赖本 -api 包；
 * 实现类放实现模块 service 层并以 @Service 暴露。
 *
 * @author PivotOS
 * @since 2.2.0
 */
public interface I${className}Facade {

    // TODO: 按业务声明契约方法，例如：
    // R<XxxDTO> getById(Long id);
}
