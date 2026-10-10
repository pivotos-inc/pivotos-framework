# Cloud 组件插件化形态设计（S133 spike 技术说明）

> 产出时间：2026-10-02 ｜ 状态：**spike 资产，非生产实现**
> 归属：`pivotos-framework/spike/s-v3-cloud/`（不进 Maven reactor、不改生产 pom、依赖数不变）
> 相关：《18-V2收官与V3启动路线图（已拍板）.md》第五节「v3.0.0 spike 计划」

---

## 0. 结论先行

| 问题 | 结论 |
|---|---|
| 通信抽象能不能做 | ✅ **能做**。Facade 接口 → HTTP 代理的替换点落在 `BeanDefinitionRegistryPostProcessor`，**业务代码零改动**即可把本地调用切成远程调用。 |
| 全量 Cloud Starter 插件化能不能做 | ✅ **能做**。五个 Starter 全部 `@ConditionalOnProperty` 驱动、纯 classpath 组合，实测 8 种形态（全引 / 全不引 / 单独引 / fail-fast）行为均符合预期。 |
| 落地成本量级 | **通信抽象 5 個 Starter 原型 ≈ 1,286 行 Java**（含注释），其中核心通信约 630 行；隐性耦合清偿实际只有 **1 条边 / 13 处 import**。 |

---

## 1. 形态定义

| 维度 | 单体形态（默认） | 微服务形态（可选） |
|---|---|---|
| 运行方式 | `java -jar pivotos-admin-server.jar`（零额外依赖） | classpath 追加 `pivotos-starter-cloud-openfeign` / `-nacos` |
| 跨 Plugin 调用 | 进程内 Spring Bean 注入 `-api` Facade | 同一批 Facade 接口走 HTTP（OpenFeign 语义） |
| 服务发现 | 不需要 | `ServiceInstanceProvider` SPI（static / nacos / 未来 k8s） |
| 分布式事务 | 本地事务 | `TxContext`（XID 传播）+ 后续二阶段/补偿 |
| 边缘/流控 | 不需要 | `cloud-gateway`（边缘头/前缀转发）+ `cloud-sentinel`（QPS 限流） |
| 业务代码 | **同一份，零 diff**（本轮实测证据：45 模块 `git diff` 零行改动） | 同左 |

**形态切换的物理本质 = classpath 组合切换**，这是「可裁剪性」最容易被验证、也最难被破坏的表达方式。

---

## 2. 五个 Cloud Starter 清单与自动配置边界

| # | Starter（spike 命名） | 职责 | 开关属性 | 缺省行为 |
|---|---|---|---|---|
| ① | `pivotos-starter-cloud-openfeign` | 通信抽象：Facade 接口 → JDK HttpClient JSON-RPC；含 HMAC 签名、`TxContext`、RPC 服务端点（`/__rpc/*` Servlet） | `pivotos.cloud.openfeign.enabled` | 不引即零行为；引入未开启亦零行为 |
| ② | `pivotos-starter-cloud-nacos` | 注册/发现：直连 Nacos **Open API v1**（注册/心跳/实例列表），实现 `ServiceInstanceProvider` | `pivotos.cloud.nacos.enabled` | 未配 `server-addr` → **fail-fast 起不来** |
| ③ | `pivotos-starter-cloud-gateway` | 统一入口：注入 `X-Edge-Gateway` / `X-Edge-Request-Id`，可选前缀剥离转发 | `pivotos.cloud.gateway.enabled` | 默认不改写任何路径 |
| ④ | `pivotos-starter-cloud-sentinel` | 流控：滑动窗口 QPS 限流，超限 429 | `pivotos.cloud.sentinel.enabled` | `qps<=0` 等于不限流 |
| ⑤ | `pivotos-starter-cloud-seata` | 事务边界：生成 XID → `TxContext` → RPC 自动携带 `X-Tx-Id` | `pivotos.cloud.seata.enabled` | 只记录，不改变业务行为 |

**自动配置边界三条铁律（沿用既有口径）**

1. **一律 `@ConditionalOnProperty` 驱动**，禁止 `@ConditionalOnBean`（踩坑 11：`@ConditionalOnBean` 存在自动配置时序窗口）。
2. **引入 ≠ 生效**：所有 Starter 的具体行为默认保守（不限流、不改写路径、不替换调用），必须显式开启。
3. **配错要响，不要默**：凡「已启用但缺必要配置」一律 fail-fast 抛出并记录日志（本次两项 fail-fast 均已实测：HMAC 缺密钥 / nacos 缺地址）。

---

## 3. 通信抽象的替换点（关键技术点）

```
业务注入点  ObjectProvider<IFileFacade>  /  @Autowired IAiFacade
                    │
                    ▼
BeanDefinitionRegistryPostProcessor（cloud-openfeign）
   · 扫描到该 Facade 的本地实现 bean（如 fileLocalFacade）
   · 原 bean 定义 clone 别名为 xxx$Local，并 setAutowireCandidate(false) ← 关键
   · 原 beanName 重新注册为 FacadeRemoteProxyFactoryBean（产出 JDK 动态代理）
                    │
                    ▼
FacadeRpcHandler.invoke()
   · 解析目标方法（按「方法名 + 参数个数 + 形参可赋值」，而非实参字面类型）
   · POST {baseUrl}/__rpc/invoke   body={interface, method, paramTypes, args}
   · 携带 X-Tx-Id（seata 已启用时）/ X-Rpc-Signature（HMAC 开启时）
   · 按 method.getGenericReturnType() 反序列化返回值
                    │
                    ▼
FacadeRpcServlet（server-enabled=true 时才注册）
   · 校验签名 → 取 xxx$Local 本地实现 → 反射调用 → JSON 返回
```

- **为什么 `$Local` 必须 `setAutowireCandidate(false)`**：否则 by-type 注入点同时看到两个候选，直接 `NoUniqueBeanDefinitionException`（本次实测踩坑 K3）。
- **为什么服务端要按名取 `$Local`**：若 `getBean(iface)` 会命中代理 → 自调用成环。
- **为什么用 Servlet 而不是 `@RestController`**：主应用组件扫描范围不覆盖 `com.pivotos.cloud.**`，Servlet 注册不受扫描影响。

---

## 4. 切到真实 Spring Cloud 的差异清单（成本项）

spike 刻意**没有**引入真实 `spring-cloud-starter-openfeign` / `spring-cloud-starter-alibaba-nacos-discovery`（详见收口报告 §4）。切换时需要处理：

| 项 | spike 现状 | 切真实 Spring Cloud 需做 |
|---|---|---|
| 依赖 | 零三方依赖（JDK HttpClient + Jackson） | 引入 cloud starter + 版本 train；本机 Spring Boot **4.1.0** 对应的 train 当时仅到 `5.1.0-M1`（非 GA），需等或锁版本 |
| 接口声明 | 运行时动态代理，不需要注解 | 需在 Facade 接口上标 `@FeignClient` **或**保留动态注册方式（推荐后者，避免污染 `-api` 模块） |
| 负载均衡 | 取第一个实例 | 换成 `@LoadBalanced` / `ReactiveLoadBalancer` |
| 降级 | 异常回落到本地实现 | 换成 Sentinel/Spring Cloud CircuitBreaker 的 fallback |
| 序列化 | Jackson JSON-RPC | 换成 Spring Cloud OpenFeign 的 `Encoder`/`Decoder`（契约不变） |
| 注册中心 | 直连 Nacos Open API v1 | 换成 `NacosServiceManager`/SDK（能力更强，SPI 不变） |

> 结论：**替换成本集中在「换客户端实现」，业务侧与 Facade 契约侧零改动**——这正是本次 spike 要验证的那件事。

---

## 5. ArchUnit 守护规则增补点

草案见同目录 `../docs/ArchUnit-guard-addendum.java`（**尚未落库**，建议 V3 正式启动时并入 `pivotos-admin-server` 既有 P0 门禁）：

- **C1** 业务 Plugin 实现层不得直接引用其它 Plugin 实现层（豁免清单按 Sprint 清偿）
- **C2** `-api` 契约模块不得反向依赖实现模块
- **C3** Facade 方法签名不得出现 `..domain.entity..`（必须可跨进程序列化）
- **C4** 数据层零跨库 JOIN —— 由 `tools/scan_cross_schema_sql.py` 在 CI 侧兜底，无需 ArchUnit

---

## 6. 已知限制（spike 阶段）

1. 五个 Starter 为**原型**，不含生产级能力：连接池复用、重试/超时策略、指标埋点、灰度开关、可观测性。
2. 身份/租户上下文未跨进程序列化（仅 XID 与签名透传）——这是 **V3 正式落地的第一优先级缺口**。
3. `nacos` / `seata` 因依赖 openfeign 的 SPI 接口而带 `@ConditionalOnClass` 保护：**单独引入且无 openfeign 时会自动失活**（不报错、不装配）。这是「不配就不做事」的裁剪原则体现，但也意味着**配错会被静默忽略**，正式版建议补一条 WARN。
4. HMAC 仅做请求体签名，未含时间戳/nonce，存在重放窗口；正式版需补。
