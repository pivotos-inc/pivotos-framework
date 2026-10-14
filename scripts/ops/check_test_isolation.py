#!/usr/bin/env python3
"""测试隔离性静态门禁：**禁止在 `static @BeforeAll` 里访问 Spring 装配的全局静态状态**。

## 为什么要有这个脚本

2026-10-12 定位了一次真实的「测试间耦合」（表现为「单跑模块红、全量绿」，
CI 上要靠 ``-Dsurefire.runOrder=alphabetical`` 压住）：

* JUnit 5 的 ``static @BeforeAll`` 执行**早于** Spring 测试上下文加载；
* 而 Sa-Token 的 ``SaManager`` 是**全局静态单例**，其 dao 为**惰性初始化**：
  首次调用 ``SaManager.getSaTokenDao()`` 时会把它锁定为内存实现
  （``SaTokenDaoDefaultImpl``），此后的 ``createLoginSession`` 造出的 token 只写进内存；
* 等上下文加载完成，sa-token 又把全局 dao 换成 Redis 实现
  （``SaTokenDaoForRedisson``）→ 请求侧读 Redis 查无此 token → 全部 ``1002``；
* 只有当**同 JVM 里已有别的测试类先加载过 Spring 上下文**时，dao 才恰好是 Redis，
  测试才绿 —— 这就是顺序依赖的全部成因。

修法只有一条：**把这类初始化挪到实例级回调**（``@BeforeEach``，或
``@TestInstance(PER_CLASS)`` + 非静态 ``@BeforeAll``），保证上下文已就绪。
这个脚本把该结论固化成可执行红线，防止它换个类再冒出来。

## 判定方式

扫描所有 ``src/test/java`` 下的 ``*.java``，找出 ``static`` 的 ``@BeforeAll`` 方法，
若其方法体内出现下列「依赖 Spring 装配的全局静态状态」的模式即 FAIL：

* ``SaManager.`` —— Sa-Token 全局单例（dao / config）
* ``Stp*Util.STP`` —— 各账号体系的 StpLogic 静态实例
* ``createLoginSession`` —— 造 token（写入当前全局 dao）
* ``loginToken(`` —— 各模块测试替身里同性质的 helper
* ``LoginContext.`` —— ScopedValue 登录上下文（上下文加载前设置无意义）

用法：
    python3 scripts/ops/check_test_isolation.py              # 校验真实源码
    python3 scripts/ops/check_test_isolation.py --selfcheck   # 负向自检（证明门禁会红）
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]

# 禁止出现在 static @BeforeAll 方法体内的模式
FORBIDDEN_PATTERNS = [
    (re.compile(r"\bSaManager\s*\."), "SaManager（全局单例，dao/config 惰性初始化）"),
    (re.compile(r"\bStp\w*Util\s*\.\s*STP\b"), "StpLogic 静态实例（token 写入当前全局 dao）"),
    (re.compile(r"\bcreateLoginSession\s*\("), "createLoginSession（造 token）"),
    (re.compile(r"\bloginToken\s*\("), "loginToken（测试替身造 token 的 helper）"),
    (re.compile(r"\bLoginContext\s*\."), "LoginContext（上下文加载前设置无意义）"),
]

BEFORE_ALL_RE = re.compile(r"^\s*@BeforeAll\s*$")
ANNOTATION_RE = re.compile(r"^\s*@")
SIGNATURE_RE = re.compile(r"^\s*(?:public|private|protected)?\s*(static\s+)?[\w<>\[\],.?\s]+\s+\w+\s*\(")


def iter_test_java_files() -> list[Path]:
    files: list[Path] = []
    for path in REPO_ROOT.rglob("src/test/java/**/*.java"):
        if "target" in path.parts:
            continue
        files.append(path)
    for path in REPO_ROOT.rglob("*/src/test/**/*.java"):
        if "target" in path.parts or path in files:
            continue
        files.append(path)
    return sorted(set(files))


def extract_method_body(lines: list[str], start: int) -> tuple[str, int]:
    """从方法签名所在行开始，按花括号配对取出方法体文本；返回（文本，结束行号）。"""
    depth = 0
    seen_open = False
    buf: list[str] = []
    idx = start
    while idx < len(lines):
        buf.append(lines[idx])
        for ch in lines[idx]:
            if ch == "{":
                depth += 1
                seen_open = True
            elif ch == "}":
                depth -= 1
        if seen_open and depth <= 0:
            break
        idx += 1
    return "\n".join(buf), idx


def scan_file(path: Path) -> list[tuple[int, str, str]]:
    """返回 [(行号, 命中模式说明, 方法名)]。"""
    try:
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    except OSError:
        return []

    hits: list[tuple[int, str, str]] = []
    idx = 0
    while idx < len(lines):
        if not BEFORE_ALL_RE.match(lines[idx]):
            idx += 1
            continue
        # 跳过 @BeforeAll 之后可能存在的其它注解行，定位方法签名
        sig_idx = idx + 1
        while sig_idx < len(lines) and ANNOTATION_RE.match(lines[sig_idx]):
            sig_idx += 1
        if sig_idx >= len(lines):
            break
        signature = lines[sig_idx]
        m = SIGNATURE_RE.match(signature)
        if not m or not m.group(1):
            # 非 static（或解析不出签名）→ 不在本门禁管辖区内
            idx = sig_idx + 1
            continue
        body, end_idx = extract_method_body(lines, sig_idx)
        for pattern, desc in FORBIDDEN_PATTERNS:
            if pattern.search(body):
                method_name = re.search(r"(\w+)\s*\(", signature)
                hits.append((sig_idx + 1, desc, method_name.group(1) if method_name else "?"))
        idx = end_idx + 1
    return hits


def main() -> int:
    args = sys.argv[1:]
    if "--selfcheck" in args:
        return selfcheck()

    files = iter_test_java_files()
    failures: list[str] = []
    for path in files:
        for line_no, desc, method in scan_file(path):
            rel = path.relative_to(REPO_ROOT)
            failures.append(f"FAIL {rel}:{line_no}  static @BeforeAll {method}() 访问了 {desc}")

    print(f"扫描测试源码 {len(files)} 个文件（规则：static @BeforeAll 内禁止访问 Spring 装配的全局静态状态）")
    if failures:
        for f in failures:
            print(f)
        print(f"\n共 {len(failures)} 项 FAIL。修法：把初始化挪到 @BeforeEach（实例级），"
              f"保证 Spring 测试上下文已加载。")
        return 1
    print("PASS 0 项违规")
    return 0


def selfcheck() -> int:
    """负向自检：门禁必须能识别出违规代码，且不得误伤合规代码。"""
    violations = """
package com.pivotos.demo;

import cn.dev33.satoken.SaManager;
import com.pivotos.starter.auth.account.StpSysUtil;
import org.junit.jupiter.api.BeforeAll;

class BadTest {
    @BeforeAll
    static void login() {
        System.out.println(SaManager.getSaTokenDao());
        StpSysUtil.STP.createLoginSession(1L);
    }
}
"""
    compliant = """
package com.pivotos.demo;

import cn.dev33.satoken.SaManager;
import com.pivotos.starter.auth.account.StpSysUtil;
import org.junit.jupiter.api.BeforeEach;

class GoodTest {
    @BeforeEach
    void login() {
        System.out.println(SaManager.getSaTokenDao());
        StpSysUtil.STP.createLoginSession(1L);
    }
}
"""
    # 合规但保留 static @BeforeAll（只做 Assumptions，不碰全局状态）也不该被判红
    compliant_static = """
package com.pivotos.demo;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;

class AssumptionsOnlyTest {
    @BeforeAll
    static void requireEnv() {
        Assumptions.assumeTrue(System.getenv("X") != null);
    }
}
"""
    results: list[tuple[str, bool, bool]] = []
    with tempfile.TemporaryDirectory() as tmp:
        for name, content in (("bad", violations), ("good", compliant), ("static_ok", compliant_static)):
            p = Path(tmp) / f"{name}.java"
            p.write_text(content, encoding="utf-8")
            hits = scan_file(p)
            results.append((name, bool(hits), len(hits) > 0))

    ok = True
    # 违规样例必须被抓到（且两条模式都命中）
    bad_name, bad_hit, _ = results[0]
    if not bad_hit:
        print(f"SELFCHECK FAIL：违规样例 {bad_name} 未被识别 —— 门禁形同虚设")
        ok = False
    else:
        print(f"SELFCHECK OK：违规样例 {bad_name} 被判红")

    for name, hit, _ in results[1:]:
        if hit:
            print(f"SELFCHECK FAIL：合规样例 {name} 被误判为违规（假阳性）")
            ok = False
        else:
            print(f"SELFCHECK OK：合规样例 {name} 未误报")

    print("负向自检 %s" % ("PASS" if ok else "FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
