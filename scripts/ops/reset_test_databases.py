#!/usr/bin/env python3
"""重建 IT 测试库（**破坏性**：DROP DATABASE 再 CREATE DATABASE）。

为什么要显式 --yes + 名单白名单：全量 IT 会写脏测试库（历史数据会让下一轮红），
重跑前必须重建；但 DROP DATABASE 一旦写错名字就是不可逆事故。因此本脚本：
  1. 只允许操作内置名单里的 8 个测试库；--only 传入名单外的名字直接报错退出；
  2. 内置名单里刻意不含 pivotos_dev / pivotos_prod 等任何业务库；
  3. 没有 --yes 只做 dry-run（打印将要做什么，不执行）；
  4. 连接串默认从 application-dev.yml 现读（口令不进命令行历史，也不打印）。

用法：
    python3 scripts/ops/reset_test_databases.py               # dry-run
    python3 scripts/ops/reset_test_databases.py --yes         # 真执行
    python3 scripts/ops/reset_test_databases.py --yes --only=test,test_message
    python3 scripts/ops/reset_test_databases.py --yes --list-existing
"""
import argparse
import os
import re
import sys

try:
    import pymysql
except ImportError:  # pragma: no cover
    sys.exit("需要 pymysql：python3 -m pip install pymysql")

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
DEV_YML = os.path.join(ROOT, "pivotos-admin-server", "src", "main", "resources", "application-dev.yml")

# 全量 IT 会写的 8 个库（与 admin-server test yml、各插件 test yml 一致）
KNOWN_TEST_DATABASES = [
    "pivotos_test",
    "test",
    "test_message",
    "test_tenant",
    "test_tenant_s1",
    "test_tenant_s2",
    "test_tenant_d1",
    "test_tenant_d2",
]

# 硬保护：这几个名字哪怕被 --only 显式传入也必须拒绝
FORBIDDEN = {"pivotos_dev", "pivotos_prod", "mysql", "information_schema", "performance_schema", "sys"}


def read_dev_defaults():
    text = open(DEV_YML, encoding="utf-8").read()

    def pick(pattern):
        m = re.search(pattern, text)
        return m.group(1).strip().strip('"') if m else ""

    return {
        "url": pick(r"url:\s*\$\{PIVOTOS_MYSQL_URL:([^}]+)\}"),
        "username": pick(r"username:\s*\$\{PIVOTOS_MYSQL_USERNAME:([^}]+)\}"),
        "password": pick(r"password:\s*\$\{PIVOTOS_MYSQL_PASSWORD:([^}]+)\}"),
    }


def parse_url(url):
    m = re.match(r"jdbc:mysql://([^:/]+)(?::(\d+))?/([^?]+)", url or "")
    if not m:
        raise SystemExit(f"无法从 application-dev.yml 解析连接串：{url!r}")
    return m.group(1), int(m.group(2) or 3306), m.group(3)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--yes", action="store_true", help="真正执行 DROP/CREATE（缺省只 dry-run）")
    parser.add_argument("--only", default="", help="逗号分隔，只重建其中一部分")
    parser.add_argument("--list-existing", action="store_true", help="只打印实例上现有的库")
    parser.add_argument("--host", default=os.getenv("PIVOTOS_MYSQL_HOST"))
    parser.add_argument("--port", type=int, default=int(os.getenv("PIVOTOS_MYSQL_PORT", "0")) or None)
    parser.add_argument("--user", default=os.getenv("PIVOTOS_MYSQL_USERNAME"))
    parser.add_argument("--password", default=os.getenv("PIVOTOS_MYSQL_PASSWORD"))
    args = parser.parse_args()

    dev = read_dev_defaults()
    if args.host and args.user:
        host, port, user, pwd = args.host, args.port or 3306, args.user, args.password or ""
    else:
        # 只用 dev yml 里的 host/port，库名换成测试库本身
        dev_host, dev_port, _db = parse_url(dev["url"])
        host, port = args.host or dev_host, args.port or dev_port
        user, pwd = args.user or dev["username"], args.password or dev["password"]
    if not pwd:
        raise SystemExit("缺少口令：用 PIVOTOS_MYSQL_PASSWORD 注入（不要写进命令行）")

    targets = [d.strip() for d in args.only.split(",") if d.strip()] or KNOWN_TEST_DATABASES
    illegal = [d for d in targets if d in FORBIDDEN or d not in KNOWN_TEST_DATABASES]
    if illegal:
        raise SystemExit(f"拒绝执行：{illegal} 不在测试库名单内（名单：{KNOWN_TEST_DATABASES}）")

    conn = pymysql.connect(host=host, port=port, user=user, password=pwd, charset="utf8mb4", autocommit=True)
    try:
        with conn.cursor() as cur:
            if args.list_existing:
                cur.execute("SHOW DATABASES")
                print(sorted(r[0] for r in cur.fetchall()))
                return 0
            cur.execute("SELECT @@version")
            version = cur.fetchone()[0]
            print(f"目标实例：{host}:{port}  MySQL {version}")
            print(f"待重建 {len(targets)} 个测试库：{targets}")
            if not args.yes:
                print("dry-run：加 --yes 才会真执行")
                return 0
            for db in targets:
                cur.execute(f"DROP DATABASE IF EXISTS `{db}`")
                cur.execute(
                    f"CREATE DATABASE `{db}` DEFAULT CHARACTER SET utf8mb4 "
                    f"COLLATE utf8mb4_general_ci")
                cur.execute(
                    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=%s", (db,))
                print(f"  ✓ {db} 重建完成，表数={cur.fetchone()[0]}")
    finally:
        conn.close()
    print(f"完成：{len(targets)} 个测试库已重建为空库（pivotos_dev 未触碰）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
