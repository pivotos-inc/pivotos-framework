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
import sys
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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", required=True, help="owner/name")
    ap.add_argument("--sha", required=True)
    ap.add_argument("--context", default="ci-failure-digest")
    ap.add_argument("--dry-run", action="store_true", help="只打印不上报（本机自测用）")
    args = ap.parse_args()

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

    if failures == 0 and errors == 0:
        description = "无测试失败（可能是构建/依赖阶段失败，见日志）"
        state = "failure"
    else:
        head = f"F{failures} E{errors}: " + "; ".join(ordered[:6])
        description = head[:MAX_DESC]
        state = "failure"

    print(f"[digest] state={state} failures={failures} errors={errors}")
    print(f"[digest] description={description}")

    if args.dry_run:
        return 0

    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        print("[digest] 未设 GITHUB_TOKEN，跳过上报（不影响构建结论）", file=sys.stderr)
        return 0

    payload = json.dumps({
        "state": state,
        "description": description,
        "context": args.context,
    }).encode()
    url = f"https://api.github.com/repos/{args.repo}/statuses/{args.sha}"
    req = urllib.request.Request(url, data=payload, method="POST", headers={
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
        "X-GitHub-Api-Version": "2022-11-28",
    })
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            print(f"[digest] 上报成功 HTTP {resp.status}")
    except urllib.error.HTTPError as e:
        # 上报失败绝不能把 CI 结论从「失败」变成「构建错误」以外的误判，只提示
        print(f"[digest] 上报失败 HTTP {e.code}: {e.read()[:200]!r}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
