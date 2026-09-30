# PivotOS Framework（pivotos-framework）

> PivotOS「一码三端」企业管理平台 —— 后端全部模块（Maven 多模块单仓），开发者：胡伟龙（Alex）
>
> 📖 在线文档：[pivotos-doc.293242.com](https://pivotos-doc.293242.com) ｜ 🖥️ PC 演示：[pivotos-pc.293242.com](https://pivotos-pc.293242.com) ｜ 📱 H5 演示：[pivotos-h5.293242.com](https://pivotos-h5.293242.com) ｜ [Apache-2.0](./LICENSE)

PivotOS 借鉴若依（RuoYi）的企业级功能面，并在 **插件化架构与 AI 原生能力** 上做差异化的企业级快速开发平台：同一套后端支撑 PC 管理端、移动 H5/App、微信小程序三端。架构特色：**轻量微内核 + 彻底插件化 + AI 原生底座 + 单代码库双部署形态（单体/微服务）**。

## 技术栈

| 类别 | 技术 |
| --- | --- |
| 语言 / 运行时 | Java 25（JDK 25，兼容矩阵含 21） |
| 核心框架 | Spring Boot 4.x · Spring Framework 7 |
| ORM | MyBatis-Plus + dynamic-datasource（多数据源/多租户） |
| 认证鉴权 | Sa-Token 多账号体系（sys-user / app-user / wx-mini-user / mind-user） |
| 数据库迁移 | Flyway（脚本随插件 jar 分发，启动自动执行，自动建库建表） |
| AI 能力 | Spring AI 2.0（OpenAI 兼容模式，多供应商动态装配） |
| 向量存储 | Milvus（主） / SimpleVectorStore（零中间件兜底），可插拔 |
| 工作流 | WarmFlow 1.8.x（内置设计器 UI） |
| 任务调度 | XXL-Job 3.x |
| 缓存 / 分布式锁 | Redis + Redisson + Lock4j |
| Excel | EasyExcel 4.x 流式（禁 POI DOM） |
| 序列化 | fastjson2（统一脱敏） |
| 架构守护 | ArchUnit（CI 每次必跑，10 条边界规则） |

## 模块结构（37 个 Maven 模块）

```
pivotos-framework (groupId=com.pivotos)
├── pivotos-dependencies        # BOM：全部第三方版本集中声明
├── pivotos-commons             # 契约层（禁依赖 Spring，ArchUnit 守护）
│   ├── pivotos-common-core     #   R<T> 统一响应 / ServiceException / 分页 / 枚举 / 上下文门面
│   └── pivotos-common-api      #   通用 API 契约
├── pivotos-starters            # 10 个技术 Starter（条件装配，可插拔）
│   ├── starter-core            #   ScopedValue 上下文体系 + fastjson2 序列化 + i18n + 链路追踪
│   ├── starter-web             #   全局异常 / 幂等 / 接口加解密 / XSS / CORS
│   ├── starter-redis           #   Redisson + Lock4j + 限流 + 租户维度缓存 Key
│   ├── starter-mybatis         #   分页/乐观锁/审计填充/字段级 AES 加密/多租户行级过滤
│   ├── starter-auth            #   Sa-Token 多账号认证
│   ├── starter-tenant          #   多租户（Column / Schema / Datasource 三模式）
│   ├── starter-excel           #   EasyExcel 流式导入导出
│   ├── starter-job             #   XXL-Job 执行器装配
│   ├── starter-ai              #   Spring AI ChatClient / EmbeddingModel 装配
│   └── starter-datascope       #   数据权限
├── pivotos-plugins             # 12 个业务插件（除 monitor 外均拆 -api 契约包 + 实现包）
│   ├── plugin-system           #   用户/角色/菜单/部门/岗位/字典/参数/公告/日志/在线用户/认证（含 mind 登录下沉）
│   ├── plugin-message          #   消息中心（站内信/模板/发送日志）
│   ├── plugin-file             #   文件存储（多云：腾讯云 COS / MinIO，预签名直传）
│   ├── plugin-workflow         #   工作流审批（WarmFlow 引擎 + 待办/已办/发起）
│   ├── plugin-ai               #   AI 对话（多供应商/多 Key/SSE 流式/会话落库）
│   ├── plugin-ai-coding        #   AI Coding（自然语言生成单表/主子/树表 CRUD）
│   ├── plugin-ai-kb            #   RAG 知识库（解析/分块/向量化/检索/重排/评测/引用溯源）
│   ├── plugin-mind             #   枢磐·智域个人端（知识库/待办/AI 拆分，配 pivotos-mind 前端）
│   ├── plugin-generator        #   代码生成器（crud/sub/tree 三模板族，PC + uni-app 双端产物）
│   ├── plugin-docsync          #   文档同步（在线文档站内容同步）
│   ├── plugin-migration        #   迁移引擎（AI 驱动的系统迁移任务编排）
│   └── plugin-monitor          #   服务监控 / 缓存监控 / 运营看板
└── pivotos-admin-server        # 单体启动器（monolith 形态，8080 端口）
```

**契约先行**：跨插件调用只允许经 `-api` 包的 Facade 接口；表前缀按域隔离（`sys_/msg_/flow_/gen_/ai_/kb_`），禁跨前缀联表；ArchUnit A1~A8 规则 CI 强制守护。

## 功能特性

- **系统管理**：用户、角色、菜单、部门、岗位、字典、参数、公告、操作日志、登录日志、在线用户、数据权限
- **多租户**：字段 / Schema / 数据源三种隔离模式，行级过滤自动追加
- **消息中心**：站内信、消息模板、发送日志、阅读状态
- **文件存储**：S3 协议多云适配（腾讯云 COS / MinIO），预签名直传 + 私有桶回显
- **工作流**：WarmFlow 流程定义（内置可视化设计器）、发起、待办、已办、审批流转 + 消息通知
- **AI 对话**：多供应商（通义/DeepSeek/Kimi/Anthropic/Gemini…OpenAI 兼容协议）、多 Key 轮换、SSE 流式、会话与消息落库、运行时动态管理（AES 加密落库）
- **AI Coding**：自然语言描述需求 → 意图识别 → 生成单表 / 主子表 / 树表完整 CRUD（后端 + PC + 移动端）
- **RAG 知识库**：文档解析（Tika 70+ 格式）→ 分块 → 向量化 → 检索；rerank 重排、查询改写、意图路由、引用溯源（chunkId 反查原文）、检索质量评测（Hit@K / MRR 跑分对比）
- **代码生成器**：选表即出 CRUD（crud / sub 主子 / tree 树三模板族），前后端 + 菜单一键导入
- **监控运维**：服务监控、缓存监控、运营看板
- **任务调度**：XXL-Job 执行器开箱集成
- **枢磐·智域（个人端）**：mind-user 第四账号体系 + 个人知识库 + 智能待办（AI 拆分）+ SSE 对话，配套前端见 [pivotos-mind](https://github.com/pivotos-inc/pivotos-mind)

## 快速开始

环境要求：JDK 25、Maven 3.9+、MySQL 8.x、Redis 6+（可选：Milvus、XXL-Job 调度中心、MinIO）

```bash
git clone https://github.com/pivotos-inc/pivotos-framework.git
cd pivotos-framework

# 1. 准备本地配置（MySQL/Redis 密码等），模板见 .env.example；
#    所有凭据经环境变量注入（MYSQL_PASSWORD / REDIS_PASSWORD / DASHSCOPE_API_KEY 等），仓库内不保留真实密钥
cp .env.example .env.dev    # 按本机实际修改

# 2. 构建（首次或依赖变更后）
mvn clean install -DskipTests

# 3. 启动（Flyway 自动建库建表；默认 profile=dev，见 application-dev.yml 数据源配置）
source .env.dev && java -jar pivotos-admin-server/target/pivotos-admin-server.jar
```

启动后：后端 API `http://localhost:8080`，默认账号 `admin / admin123`。

- 生产部署（1Panel / JAR + Nginx）见在线文档《部署指南》
- 统一响应 `R<T>`（`code=0` 成功）、分页 `pageNum/pageSize`、Token = `Authorization` 头裸值

## 相关仓库

| 仓库 | 说明 |
| --- | --- |
| [pivotos-ui](https://github.com/pivotos-inc/pivotos-ui) | PC 管理端（Vue3 + Element Plus，pnpm Monorepo） |
| [pivotos-app](https://github.com/pivotos-inc/pivotos-app) | 移动端（uni-app 一码三端：H5 / App / 小程序） |
| [pivotos-mind](https://github.com/pivotos-inc/pivotos-mind) | 枢磐·智域个人端（C 端 AI 助手开源样板间，对接本仓 plugin-mind） |
| [pivotos-docs](https://github.com/pivotos-inc/pivotos-docs) | 项目文档库（PRD / 架构 / 规范 / 流程 / 踩坑记录） |
| pivotos-docsite | 在线文档站源码（VitePress，部署于 pivotos-doc.293242.com） |

## 维护约定

> 新增 Starter / Plugin / 功能特性时，须同步更新本 README 的「模块结构」「功能特性」两节及在线文档对应章节。

## License

[Apache License 2.0](./LICENSE) · Copyright 2026 胡伟龙（Alex）

凭据安全约定：`.env.dev` 等含真实凭据的文件一律不入库（`.gitignore` 已覆盖）；配置文件中凭据一律 `${ENV_VAR:}` 占位经环境变量注入。
