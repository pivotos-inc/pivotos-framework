# ${displayName}插件 · Flyway 迁移占位说明

> 本目录（`src/main/resources/db/migration`）为本插件的 Flyway 迁移目录，
> admin-server 启动时自动并入全局迁移序列（SQL 放插件目录、版本号全局递增）。

## 建表迁移落笔规则（踩坑纪律）

1. **版本号全局递增**：先看全仓最新版本号（当前已到 V1.2.17），新迁移取下一号，
   命名 `Vx.y.z__${tablePrefix}init.sql`；多人并行撞号时先推 develop 者占号。
2. **表前缀**：本插件表一律 `${tablePrefix}` 前缀，并登记 ArchUnit
   `P0ArchitectureTest` A8 表前缀白名单。
3. **红线**：禁 `AUTO_INCREMENT`（主键雪花，实体继承 BaseDO ASSIGN_ID）；
   需租户隔离的表继承 TenantBaseDO（tenant_id NOT NULL 时业务层显式 setTenantId 回落 0，
   共享表登记 TenantProperties.ignoreTables）。
4. **审计字段**：`create_by/update_by` 用 BIGINT（S36 曾用 VARCHAR 落库即炸，见 V1.2.14 修复）。
5. **菜单 SQL**：与建表迁移分开一个版本号，模板见 `docs/menu.sql.template`。
