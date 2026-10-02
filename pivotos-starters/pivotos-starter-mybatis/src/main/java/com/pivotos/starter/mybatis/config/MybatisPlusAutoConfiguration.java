package com.pivotos.starter.mybatis.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.TenantLineInnerInterceptor;
import com.pivotos.starter.core.context.TenantContext;
import com.pivotos.starter.mybatis.config.properties.MybatisProperties;
import com.pivotos.starter.mybatis.crypto.FieldEncryptCrypto;
import com.pivotos.starter.mybatis.guard.DataSourceUrlGuard;
import com.pivotos.starter.mybatis.handler.AuditMetaObjectHandler;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/**
 * MyBatis-Plus 自动配置：租户行级过滤 / 分页 / 乐观锁 / 防全表更新 + 审计填充 + 字段加密。
 * 租户策略为默认实现：TenantContext 未绑定（单租户模式）时所有表放行，
 * 完整策略（字段/schema/datasource 三模式）由 P1 tenant Starter 提供。
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(MybatisProperties.class)
public class MybatisPlusAutoConfiguration {

    public MybatisPlusAutoConfiguration(MybatisProperties properties, Environment environment) {
        // 字段加密密钥注入静态 holder（TypeHandler 由 MyBatis 实例化）
        if (StringUtils.hasText(properties.getFieldEncryptKey())) {
            FieldEncryptCrypto.init(properties.getFieldEncryptKey());
        }
        // L9：启动期打印「生效库 + 注入源」，并按需 fail-fast
        DataSourceUrlGuard.Report report = DataSourceUrlGuard.resolve(environment);
        log.info("[DataSource] 生效数据源溯源：{}", report);
        String mismatch = DataSourceUrlGuard.mismatch(report, properties.getExpectedDatabase());
        if (mismatch != null) {
            log.error("[DataSource] 数据源与期望库不一致（L9 守卫）：{}", mismatch);
            throw new IllegalStateException("数据源与期望库不一致（L9 守卫）：" + mismatch);
        }
    }

    /**
     * 默认租户行级处理器：TenantContext 未绑定（单租户模式）时所有表放行。
     * 抽为可替换 Bean（装配点）：P1 tenant Starter 在 column 模式下注册增强实现
     * （ignore-tables 等）自动替换本默认实现，业务代码 0 改动。
     */
    @Bean
    @ConditionalOnMissingBean(TenantLineHandler.class)
    public TenantLineHandler tenantLineHandler(MybatisProperties properties) {
        return new TenantLineHandler() {
            @Override
            public Expression getTenantId() {
                Long tenantId = TenantContext.get();
                return new LongValue(tenantId == null ? 0L : tenantId);
            }

            @Override
            public String getTenantIdColumn() {
                return properties.getTenantColumn();
            }

            @Override
            public boolean ignoreTable(String tableName) {
                // 默认策略：无租户上下文 → 不过滤任何表
                return TenantContext.get() == null;
            }
        };
    }

    /**
     * MP 插件链。顺序约定：租户 → 分页 → 乐观锁 → 防全表更新。
     * TenantLineHandler 经 ObjectProvider 延迟解析（历史经验：跨自动配置禁用
     * @ConditionalOnBean 直接注入，装配顺序敏感）。
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(ObjectProvider<TenantLineHandler> tenantLineHandlerProvider) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 租户行级过滤：handler 可被 tenant Starter 增强替换；未绑定租户上下文时全表放行（单租户模式 0 改动）
        TenantLineInnerInterceptor tenantInterceptor = new TenantLineInnerInterceptor();
        tenantInterceptor.setTenantLineHandler(tenantLineHandlerProvider.getObject());
        interceptor.addInnerInterceptor(tenantInterceptor);

        // 分页（单页上限与 PageQuery 对齐）
        PaginationInnerInterceptor paginationInterceptor = new PaginationInnerInterceptor();
        paginationInterceptor.setMaxLimit(500L);
        interceptor.addInnerInterceptor(paginationInterceptor);

        // 乐观锁（实体带 @Version 字段才生效）
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());

        // 防全表更新/删除
        interceptor.addInnerInterceptor(new BlockAttackInnerInterceptor());
        return interceptor;
    }

    /**
     * 审计字段自动填充
     */
    @Bean
    public AuditMetaObjectHandler auditMetaObjectHandler() {
        return new AuditMetaObjectHandler();
    }
}
