# pivotos-starter-tenant

多租户技术底座：字段（column）/ schema / datasource 三种隔离模式，策略模式统一抽象，
**默认关闭**——加依赖不启用时行为与无依赖完全一致。

## 功能

- 三模式租户隔离（Strategy 模式，按 `pivotos.tenant.mode` 三选一装配）
- 租户上下文绑定：每请求解析租户 → `TenantContext`（ScopedValue）绑定，异步经 `ContextExecutor` 自动透传
- 登录链路增强经扩展点兑现：只读 common-api `LoginUser` / starter-core `LoginContext`，**对 auth 零反向依赖**
- column 模式行级过滤（MyBatis-Plus `TenantLineInnerInterceptor`）+ 审计自动填充 `tenant_id`
- schema / datasource 模式经 dynamic-datasource 按租户路由数据源
- 缓存维度隔离复用 starter-redis `TenantCacheKeyGenerator`（key 自动含租户段）

## 快速开始

```xml
<dependency>
    <groupId>com.pivotos</groupId>
    <artifactId>pivotos-starter-tenant</artifactId>
</dependency>
```

```yaml
pivotos:
  tenant:
    enabled: true        # 默认 false，不写=单租户
    mode: column         # column / schema / datasource
```

业务表实体继承 `TenantBaseDO`（starter-mybatis），表含 `tenant_id` 列——0 行业务代码，隔离即生效。

## 配置项（`pivotos.tenant.*`）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关（条件装配锚点） |
| `mode` | `column` | 隔离模式：`column` / `schema` / `datasource` |
| `header-name` | `X-Tenant-Id` | 请求头租户解析来源 |
| `ignore-tables` | `[]` | 追加的行级过滤忽略表（与内置 sys_* + flyway_schema_history 取并集） |
| `ignore-urls` | `[/auth/login, /auth/logout]` | 不绑定租户上下文的接口（Ant 风格） |
| `strict` | `false` | `true` 时解析不到租户 → 403（code 1003） |
| `schema-map` | `{}` | schema 模式：租户 ID → 数据源 key |
| `datasource-map` | `{}` | datasource 模式：租户 ID → 数据源 key |

租户列名读 `pivotos.mybatis.tenant-column`（默认 `tenant_id`），两处不要配成不同值。

## 三种模式怎么选

| 模式 | 机制 | 运维成本 | 隔离强度 | 适用 |
| --- | --- | --- | --- | --- |
| column | 共享库表，SQL 自动追加 `tenant_id = ?` | 最低 | 行级（依赖 SQL 改写全覆盖） | 中小规模 SaaS，**默认首选** |
| schema | 同实例独立 schema，按租户路由连接 | 中（每租户建 schema + 各自迁移） | 库级，可租户级备份/迁移 | 中大规模、合规要求租户数据可单独导出 |
| datasource | 独立数据源（可跨实例） | 最高 | 物理隔离 | 大租户独立部署、 noisy-neighbor 隔离 |

### schema / datasource 模式配置示例

```yaml
spring:
  datasource:
    dynamic:
      primary: master                    # 平台库（sys_* 共享数据落这里）
      datasource:
        master: { url: jdbc:mysql://host:3306/platform, ... }
        tenant_1001: { url: jdbc:mysql://host:3306/tenant_1001, ... }
pivotos:
  tenant:
    enabled: true
    mode: schema                         # 或 datasource
    schema-map: { 1001: tenant_1001 }    # datasource 模式用 datasource-map
```

- 未配置映射的租户 / 无租户请求 → `primary` 数据源（平台库语义）。
- 路由模式下 MP 行级过滤整体放行（隔离已由路由完成）；各租户库需自行完成表结构迁移（Flyway 只迁移 primary）。
- **限制**：路由上下文是 dynamic-datasource 的 ThreadLocal 实现，`ContextExecutor` 异步任务不带出租源切换；异步跨租户查询需显式 `@DS` 或 `DynamicDataSourceContextHolder.push/poll`。

## 租户解析与扩展点

默认解析器 `DefaultTenantResolver` 优先级链：

1. `LoginUser.tenantId`（登录态携带，为后续"登录选租户"功能预留的载体）
2. 请求头 `X-Tenant-Id`
3. 解析不到 → `null`（单租户行为，全链路放行；`strict=true` 时 403）

**业务侧替换**：注册自己的 `TenantResolver` Bean（如从用户-租户关系表解析），
`@ConditionalOnMissingBean` 守护自动替换默认实现，无需改本 Starter 与 auth。

## 平台共享表约定（S14 设计评审 D1 决议）

`sys_*` 全部 11 张表为**平台共享表**（菜单/字典/配置天然平台级），内置进 `ignore-tables`，
多租户启用后行为不变。租户管理业务（sys_tenant / 用户-租户绑定）不属于本 Starter，
后续以 Plugin 形式提供并实现 `TenantResolver` SPI 接入。

## 验证

- `TenantDisabledAssemblyTest`：默认关闭时容器 0 个 tenant Bean
- `TenantColumnIsolationTest`：两租户互不可见 + 审计填充实证 + 单租户放行 + ignore-tables 追加语义
- `TenantSchemaRoutingTest` / `TenantDatasourceRoutingTest`：纯 JDBC 直连实证数据物理落点
- `TenantColumnStrictTest`：strict 模式 403
- 测试库独立（test_tenant / test_tenant_s1·s2 / test_tenant_d1·d2），复跑前 jshell 重建
