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
import com.pivotos.starter.mybatis.handler.AuditMetaObjectHandler;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.util.StringUtils;

/**
 * MyBatis-Plus 自动配置：租户行级过滤 / 分页 / 乐观锁 / 防全表更新 + 审计填充 + 字段加密。
 * 租户策略为默认实现：TenantContext 未绑定（单租户模式）时所有表放行，
 * 完整策略（字段/schema/datasource 三模式）由 P1 tenant Starter 提供。
 */
@AutoConfiguration
@EnableConfigurationProperties(MybatisProperties.class)
public class MybatisPlusAutoConfiguration {

    public MybatisPlusAutoConfiguration(MybatisProperties properties) {
        // 字段加密密钥注入静态 holder（TypeHandler 由 MyBatis 实例化）
        if (StringUtils.hasText(properties.getFieldEncryptKey())) {
            FieldEncryptCrypto.init(properties.getFieldEncryptKey());
        }
    }

    /**
     * MP 插件链。顺序约定：租户 → 分页 → 乐观锁 → 防全表更新
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor(MybatisProperties properties) {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();

        // 租户行级过滤：未绑定租户上下文时全表放行（单租户模式 0 改动）
        TenantLineInnerInterceptor tenantInterceptor = new TenantLineInnerInterceptor();
        tenantInterceptor.setTenantLineHandler(new TenantLineHandler() {
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
        });
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
