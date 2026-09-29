#!/usr/bin/env python3
"""S115 E2E 测试资产公共模块（一）：dev 库连接与 fixture 物理自清。

背景：本机 dev 库（pivotos_dev）里的 E2E fixture 反复出现「迁移任务行丢失 / 样图丢失」型漂移
（S108 K2 / S114 §5.1）。复壮的总原则是**不留下依赖**：fixture 由脚本自建、跑完自清，
而关键粘连点是几个 DELETE 端点不存在或被业务守卫生效拦住——
  · migration 域无 DELETE 端点（`MigrationTaskController` 只有 create/complete/rollback）；
  · 流程定义删除守卫（S109 K31）会让「挂过实例」的定义删不掉，
    即使实例已 terminate（守卫按 del_flag=0 的实例计数）。
故统一在这里提供 dev 库物理清理能力，供各 E2E 脚本按 taskId / instanceId / definitionId 精确自清，
**只删脚本自己建的行**，不动任何存量数据。

凭据以 pivotos-admin-server/src/main/resources/application-dev.yml 明文为准，支持环境变量覆盖。
"""
import os

import pymysql

DB = dict(
    host=os.environ.get("PIVOTOS_DEV_MYSQL_HOST", "175.24.176.176"),
    port=int(os.environ.get("PIVOTOS_DEV_MYSQL_PORT", "3306")),
    user=os.environ.get("PIVOTOS_DEV_MYSQL_USER", "root"),
    password=os.environ.get("PIVOTOS_DEV_MYSQL_PASSWORD", "mysql_DNCi3f"),
    database=os.environ.get("PIVOTOS_DEV_MYSQL_DATABASE", "pivotos_dev"),
    charset="utf8mb4",
)


def connect_db():
    return pymysql.connect(**DB)


def delete_rows(table, column, values, conn=None):
    """按列批量删除行，返回删除行数。values 可以是标量或序列。"""
    if not isinstance(values, (list, tuple, set)):
        values = [values]
    values = list(values)
    if not values:
        return 0

    def run(c):
        with c.cursor() as cur:
            placeholders = ",".join(["%s"] * len(values))
            cur.execute(f"DELETE FROM {table} WHERE {column} IN ({placeholders})", values)
            return cur.rowcount

    if conn is not None:
        n = run(conn)
        conn.commit()
        return n
    conn = connect_db()
    try:
        n = run(conn)
        conn.commit()
        return n
    finally:
        conn.close()


def purge_flow_instances(instance_ids):
    """物理清理流程实例及其附属行（抄送/历史/办理人/任务），返回按表统计的删除行数。

    顺序有讲究：flow_user 通过 task_id 关联任务，必须先取到 task_id 再删 flow_task。
    """
    ids = list(instance_ids)
    conn = connect_db()
    try:
        with conn.cursor() as cur:
            cur.execute("SELECT id FROM flow_task WHERE instance_id IN (%s)"
                        % ",".join(["%s"] * len(ids)), ids)
            task_ids = [r[0] for r in cur.fetchall()]
        # flow_user.associated 存的是 task_id（warm-flow 1.8.7 约定，实测确认）
        removed = {"flow_user": delete_rows("flow_user", "associated", task_ids, conn=conn)}
        for table, column in (("flow_cc", "instance_id"),
                              ("flow_his_task", "instance_id"),
                              ("flow_task", "instance_id"),
                              ("flow_instance", "id")):
            removed[table] = delete_rows(table, column, ids, conn=conn)
        return removed
    finally:
        conn.close()


def purge_flow_definition(definition_id):
    """物理清理流程定义及其节点/连线（含历史版本行），返回按表统计的删除行数。"""
    removed = {}
    for table, column in (("flow_node", "definition_id"),
                          ("flow_skip", "definition_id"),
                          ("flow_definition", "id")):
        removed[table] = delete_rows(table, column, [definition_id])
    return removed
