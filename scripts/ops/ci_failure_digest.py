#!/usr/bin/env python3
"""把 CI 失败摘要写进 **commit status**，供无令牌的人（含公开仓匿名访问）直接读。

为什么需要这个：
  GitHub Actions 的**日志下载与 artifact 下载都要求仓库 admin 权限**（实测匿名请求
  `GET /repos/{o}/{r}/actions/jobs/{id}/logs` 返回 403 "Must have admin rights to Repository"）。
  于是一个 public 仓库的 CI 红了之后，不持令牌的人只能看到「哪个 step 红了」，
  **看不到为什么红** —— 排查只能靠猜。

  而 commit status（`/statuses`）对 public 仓库是**匿名可读**的，且其 `description` 有 140 字符，
  足够放「红在哪几个测试类、几条失败」这样的定位信息。

用法（ci.yml 里，只在失败时跑）：
    - name: 失败摘要上报
      if: failure()
      env:
        GITHUB_TOKEN: ${{ secrets.GITHUB_TOKEN }}
      run: python3 scripts/ops/ci_failure_digest.py --repo ${{ github.repository }} --sha ${{ github.sha }}

读取（无需令牌）：
    curl https://api.github.com/repos/pivotos-inc/pivotos-framework/commits/<sha>/statuses
"""
import argparse
import glob
import json
import os
import re
import socket
import sys
import time
import urllib.error
import urllib.request

# surefire/failsafe 的 .txt 摘要里长这样：
#   Tests run: 12, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.5 s <<< FAILURE! -- in XxxTest
SUMMARY_RE = re.compile(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+).*?in (\S+)")
# .txt 里失败用例的标题行：
#   XxxTest.some_case -- Time elapsed 0.01 s <<< FAILURE!
CASE_RE = re.compile(r"^\s*(\S+)\.(\S+)\s+--\s+Time elapsed.*(FAILURE|ERROR)!", re.M)
# XML 里 <error>/<failure> 的 message 首行，用于兜底
MAX_DESC = 140


def collect_xml_errors():
    """从 surefire/failsafe 的 XML 里取「第一个」失败的异常类型与消息片段。

    光有类名不够定位（「DemoAcceptanceTest 6 个 Error」仍然不知道为什么红），
    真正有信息量的是异常类型：BeanCreationException ≈ 上下文起不来、
    RedisConnectionException ≈ 中间件不通、NoSuchMethodError ≈ 依赖版本错配。
    """
    import xml.etree.ElementTree as ET
    for pattern in ("**/target/surefire-reports/**/*.xml", "**/target/failsafe-reports/**/*.xml"):
        for path in glob.glob(pattern, recursive=True):
            try:
                root = ET.parse(path).getroot()
            except Exception:
                continue
            for case in root.iter("testcase"):
                for kind in ("error", "failure"):
                    node = case.find(kind)
                    if node is None:
                        continue
                    etype = node.get("type") or kind
                    message = (node.get("message") or "").strip().replace("\n", " ")
                    short = etype.split(".")[-1]
                    return short, message
    return None, None


def collect(report_dirs):
    """返回 (失败总数, 错误总数, [摘要行])"""
    failures = errors = 0
    lines = []
    for pattern in report_dirs:
        for path in glob.glob(pattern, recursive=True):
            try:
                with open(path, "r", encoding="utf-8", errors="replace") as f:
                    text = f.read()
            except OSError:
                continue
            for m in SUMMARY_RE.finditer(text):
                f_, e_ = int(m.group(2)), int(m.group(3))
                if f_ or e_:
                    failures += f_
                    errors += e_
                    lines.append(f"{m.group(5)} F{f_}E{e_}")
            for m in CASE_RE.finditer(text):
                lines.append(f"case:{m.group(1)}.{m.group(2)}")
    return failures, errors, lines


def probe_env():
    """探测 CI 里的中间件与机器环境 —— 本机绿 / runner 红这类差异，只能靠现场取证。

    只用标准库：runner 上没有 pymysql。
    """
    parts = []
    # MySQL：握手包里带版本号（明文，可直接读）
    try:
        with socket.create_connection(("127.0.0.1", 3306), timeout=5) as s:
            s.settimeout(5)
            greeting = s.recv(200)
        idx = greeting.find(b"\x00", 5)
        version = greeting[5:idx].decode("utf-8", "replace") if idx > 0 else "?"
        parts.append(f"MySQL {version}")
    except Exception as e:
        parts.append(f"MySQL {type(e).__name__}")
    # Redis：无密码会直接 PONG；要求密码会回 -NOAUTH（都算通）
    try:
        with socket.create_connection(("127.0.0.1", 6379), timeout=5) as s:
            s.settimeout(5)
            s.sendall(b"*1\r\n$4\r\nPING\r\n")
            reply = s.recv(80).decode("utf-8", "replace").strip()
        parts.append(f"Redis {reply[:12]}")
    except Exception as e:
        parts.append(f"Redis {type(e).__name__}")
    parts.append("TZ=" + (os.environ.get("TZ") or time.strftime("%Z")))
    parts.append(time.strftime("%H:%M"))
    return " | ".join(parts)


def publish(repo, sha, context, state, description, token):
    payload = json.dumps({"state": state, "description": description[:MAX_DESC],
                          "context": context}).encode()
    req = urllib.request.Request(f"https://api.github.com/repos/{repo}/statuses/{sha}",
                                 data=payload, method="POST", headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            print(f"[digest] {context} 上报成功 HTTP {resp.status}")
    except urllib.error.HTTPError as e:
        print(f"[digest] {context} 上报失败 HTTP {e.code}: {e.read()[:160]!r}", file=sys.stderr)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="owner/name")
    ap.add_argument("--sha", required=True)
    ap.add_argument("--context", default="ci-failure-digest")
    ap.add_argument("--dry-run", action="store_true", help="只打印不上报（本机自测用）")
    ap.add_argument("--report", metavar="CONTEXT", help="直接上报一条 status（配合 --text/--state）")
    ap.add_argument("--text", help="--report 的描述文本")
    ap.add_argument("--state", default="failure", choices=["failure", "success", "error"])
    args = ap.parse_args()

    if args.report:
        # 给 CI 里的「二分定位」步骤用：把任意一段结论写进 status，供匿名读取
        token = os.environ.get("GITHUB_TOKEN")
        if not token:
            print("[digest] 未设 GITHUB_TOKEN，跳过上报", file=sys.stderr)
            return 0
        publish(args.repo, args.sha, args.report, args.state, args.text or "", token)
        return 0

    patterns = [
        "**/target/surefire-reports/**/*.txt",
        "**/target/failsafe-reports/**/*.txt",
    ]
    failures, errors, lines = collect(patterns)

    # 去重保序，优先保留用例级（信息量更大），再补类级
    seen, ordered = set(), []
    for line in sorted(lines, key=lambda s: (not s.startswith("case:"), s)):
        if line not in seen:
            seen.add(line)
            ordered.append(line)

    etype_short, message = collect_xml_errors()
    if failures == 0 and errors == 0:
        description = "无测试失败（可能是构建/依赖阶段失败）"
        if etype_short:
            description = f"无测试失败，异常={etype_short}"
        state = "failure"
    else:
        # 排序：异常类型 > **异常消息** > 用例名。
        # 「AssertionError」只能说明是断言红，「expected:<0> but was:<1002>」才说明是没登录 ——
        # 后者才是能直接动手的信息（2026-10-12 第五轮 CI 就卡在这一层）。
        head = f"F{failures} E{errors} {etype_short or ''}: {message}" if message else \
            f"F{failures} E{errors} {etype_short or ''}: " + "; ".join(ordered[:4])
        description = head[:MAX_DESC]
        state = "failure"
    if message:
        print(f"[digest] 首个异常消息={message[:300]}")

    print(f"[digest] state={state} failures={failures} errors={errors}")
    print(f"[digest] description={description}")

    if args.dry_run:
        return 0

    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        print("[digest] 未设 GITHUB_TOKEN，跳过上报（不影响构建结论）", file=sys.stderr)
        return 0

    publish(args.repo, args.sha, args.context, state, description, token)

    # 分段续报：一个 status 只有 140 字符，装不下完整消息与现场信息。
    # 出现「本机绿 / runner 红」时，靠的就是这几段（2026-10-12 第五轮 CI 正是卡在这里）。
    for i, chunk_start in enumerate(range(0, len(message), MAX_DESC), start=1):
        chunk = message[chunk_start:chunk_start + MAX_DESC]
        if chunk:
            publish(args.repo, args.sha, f"{args.context}-msg{i}", state, chunk, token)
    publish(args.repo, args.sha, f"{args.context}-env", state, probe_env(), token)
    return 0


if __name__ == "__main__":
    sys.exit(main())
