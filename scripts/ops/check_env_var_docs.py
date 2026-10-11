#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""环境变量文档防漂移门禁（V3.1-G · B6）。

为什么需要它
------------
`docs/deploy/env-vars.md` **已经失效过两次**：

  1. 旧版列过 `MYSQL_HOST` / `REDIS_HOST` / `XXL_JOB_ADMIN` / `DASHSCOPE_API_KEY`
     等一批变量——这些名字代码根本不读。运维照着配，改了不生效，而且**没有任何报错**。
     这类文档不是「过时」，是**反向杀伤**：它让人确信自己做对了，于是不会再去找真原因。
  2. V3.1-A 重写后又漏了 10 个真实生效的变量（熔断组 7 个、小程序 secret、
     Nacos 口令/分组），修订 `PIVOTOS_FIELD_ENCRYPT_KEY_GUARD_ENABLED` 时
     还写错了一层前缀（真实键是 `pivotos.mybatis.field-encrypt-key-guard-enabled`）。

根因是「靠人记」。所以改成机器比对：

  · 代码侧真相 = admin-server 的 application*.yml 里所有 `${PIVOTOS_*:...}` 占位符
                 + Java 侧形如 `pivotos.xxx-yyy` 的属性键字面量（松散绑定转环境变量名）
  · 文档侧真相 = docs/deploy/env-vars.md 里出现的所有 `PIVOTOS_*` 标识

  双向都要干净：
    - 代码有 / 文档没写  → 文档缺项，运维不知道有这个开关
    - 文档有 / 代码不读  → **反向杀伤**，运维配了不生效

退出码：0 = 无漂移；1 = 存在漂移；2 = 找不到文件。

用法：
    python3 scripts/ops/check_env_var_docs.py
"""

from __future__ import annotations

import os
import re
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES_DIR = os.path.join(ROOT, "pivotos-admin-server", "src", "main", "resources")
DOCSITE = os.path.abspath(os.path.join(ROOT, "..", "pivotos-docsite"))
DOC = os.path.join(DOCSITE, "docs", "deploy", "env-vars.md")

YML_PLACEHOLDER = re.compile(r"\$\{([A-Za-z0-9_]+)(?::([^}]*))?\}")

# 「声明式」登记：这几个变量在 application*.yml 里**没有**占位符出口
# （默认值写在 Java 配置类的字段上），但**确实**能被同名环境变量覆盖（Spring 松散绑定）。
# 扫描器发现不了它们，故显式登记并给出出处；若哪天它们进了 yml，
# 本脚本会判「声明已过期」并 FAIL，防止登记表变成新的藏污纳垢处。
DECLARED_JAVA_SIDE_VARS = {
    "PIVOTOS_OPS_HEALTH_ENABLED":
        "OpsHealthProperties#enabled（@ConfigurationProperties(prefix=pivotos.ops.health)）",
    "PIVOTOS_OPS_HEALTH_TIMEOUTMS":
        "OpsHealthProperties#timeoutMs",
    "PIVOTOS_OPS_HEALTH_SHOWDETAILSANONYMOUSLY":
        "OpsHealthProperties#showDetailsAnonymously",
    "PIVOTOS_MYBATIS_FIELD_ENCRYPT_KEY_GUARD_ENABLED":
        "FieldEncryptKeyGuard.GUARD_ENABLED_KEY = pivotos.mybatis.field-encrypt-key-guard-enabled",
}


def collect_code_vars() -> dict[str, str]:
    """返回 {环境变量名: 来源说明}。

    <p>真相来源刻意限定为 <b>yml 占位符</b>：运维真正需要注入的就是这一层。
    早期版本还扫了 Java 字符串字面量，结果把 {@code pivotos.ai.calls}、
    {@code pivotos.http.slow} 这类<b>指标名常量</b>也当成环境变量收进来了，
    产生一批无法消除的假阳性——故回退为只认 yml，Java 侧改走上面的显式登记。
    """
    out: dict[str, str] = {}

    for name in sorted(os.listdir(RES_DIR)):
        if not name.endswith(".yml"):
            continue
        if not (name == "application.yml" or name.startswith("application-")):
            continue
        text = open(os.path.join(RES_DIR, name), encoding="utf-8").read()
        for var, default in YML_PLACEHOLDER.findall(text):
            if var.startswith("PIVOTOS_"):
                out.setdefault(var, f"{name} 占位符，默认={(default or '空')!r}")

    for var, source in DECLARED_JAVA_SIDE_VARS.items():
        out.setdefault(var, f"Java 侧声明（{source}）")

    return out


def collect_doc_vars() -> set[str]:
    text = open(DOC, encoding="utf-8").read()
    return set(re.findall(r"PIVOTOS_[A-Z0-9_]+", text))


def main() -> int:
    missing = []
    for path in (RES_DIR, DOC):
        if not os.path.exists(path):
            print(f"❌ 路径不存在：{path}", file=sys.stderr)
            return 2

    code = collect_code_vars()
    doc = collect_doc_vars()

    undocumented = sorted(set(code) - doc)
    phantom = sorted(doc - set(code))
    # 登记表自洁：一旦某变量在 yml 里有了占位符出口，这里的声明就必须删掉
    yml_vars = {v for v in code if code[v].endswith("占位符，默认='空'")
                or " 占位符，默认=" in code[v]}
    stale = sorted(set(DECLARED_JAVA_SIDE_VARS) & yml_vars)

    print("=" * 78)
    print("环境变量文档防漂移比对")
    print("=" * 78)
    print(f"代码侧生效变量：{len(code)} 个｜文档侧登记变量：{len(doc)} 个")

    if undocumented:
        print("\n❌ 代码有、文档没写（文档缺项）：")
        for var in undocumented:
            print(f"   - {var}    ← {code[var]}")
    else:
        print("\n✅ 无缺项：代码里所有 PIVOTOS_* 变量都已在文档中登记")

    if phantom:
        print("\n❌ 文档有、代码不读（反向杀伤：配了不生效且不报错）：")
        for var in phantom:
            print(f"   - {var}")
    else:
        print("✅ 无反向杀伤项：文档里登记的变量都能在代码中找到出口")

    if stale:
        print("\n❌ 声明已过期（变量已在 yml 有出口，应从 DECLARED_JAVA_SIDE_VARS 删除）：")
        for var in stale:
            print(f"   - {var}")

    print("-" * 78)
    if undocumented or phantom or stale:
        print(f"❌ 存在漂移：缺项 {len(undocumented)} 个，反向杀伤 {len(phantom)} 个，"
              f"过期声明 {len(stale)} 个。")
        return 1
    print("✅ 文档与代码一致。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
