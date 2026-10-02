#!/usr/bin/env python3
"""S124 · fixture 自愈框架自测（零外部资源，可离线跑）。

用内存假 fixture 钉死框架的六条契约——历史上 E2E 的 fixture 漂移本质就是这六条
没有任何一条被代码保证过。

运行：cd pivotos-framework && python3 scripts/e2e/fixture_selfheal_test.py
"""
from __future__ import annotations

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from fixture_selfheal import (  # noqa: E402
    Fixture,
    FixtureCleanupError,
    FixtureUnavailable,
    ensure,
    managed,
)

PASS = 0
FAIL = 0


def check(name: str, cond: bool, detail: str = "") -> None:
    global PASS, FAIL
    if cond:
        PASS += 1
        print(f"  PASS  {name}")
    else:
        FAIL += 1
        print(f"  FAIL  {name}  {detail}")


class FakeFixture(Fixture):
    """可控假 fixture：health 手动置位，计数 probe/heal/cleanup 调用次数。"""

    name = "fake"

    def __init__(self, health: bool = False, heal_works: bool = True,
                 cleanup_works: bool = True, hint: str = " FakeHint"):
        self.health = health
        self.heal_works = heal_works
        self.cleanup_works = cleanup_works
        self._hint = hint
        self.probes = 0
        self.heals = 0
        self.cleanups = 0

    def probe(self):
        self.probes += 1
        return self.health

    def heal(self):
        self.heals += 1
        if self.heal_works:
            self.health = True

    def cleanup(self):
        self.cleanups += 1
        if self.cleanup_works:
            self.health = False
            return 1
        return 0  # 清了但没清掉 → 复核必然失败

    def hint(self):
        return self._hint


def t01_healthy_needs_no_heal():
    fx = FakeFixture(health=True)
    ok = ensure(fx, verbose=False)
    check("健康时 ensure 返回 True", ok is True)
    check("健康时不触发 heal", fx.heals == 0, f"heals={fx.heals}")


def t02_heal_exactly_once():
    fx = FakeFixture(health=False, heal_works=True)
    ok = ensure(fx, verbose=False)
    check("不健康时 ensure 自建后返回 True", ok is True)
    check("heal 恰好调用一次（不无限重试）", fx.heals == 1, f"heals={fx.heals}")
    check("自建后复核 probe 至少 2 次", fx.probes >= 2, f"probes={fx.probes}")


def t03_unavailable_raises_with_hint():
    fx = FakeFixture(health=False, heal_works=False, hint="先起 8080")
    raised = None
    try:
        ensure(fx, verbose=False)
    except FixtureUnavailable as exc:
        raised = exc
    check("自建失败抛 FixtureUnavailable", raised is not None)
    check("异常带处置提示", raised is not None and "先起 8080" in str(raised), str(raised))
    check("不可得时 heal 仍只调一次", fx.heals == 1, f"heals={fx.heals}")


def t04_managed_cleans_up():
    fx = FakeFixture(health=False)
    with managed(fx, verbose=False):
        check("managed 进入即已就绪", fx.health is True)
    check("正常退出必 cleanup", fx.cleanups == 1, f"cleanups={fx.cleanups}")
    check("退出后回到不健康（归零）", fx.health is False)


def t05_managed_cleans_up_on_exception():
    fx = FakeFixture(health=False)
    caught = None
    try:
        with managed(fx, verbose=False):
            raise ValueError("被断言打挂")
    except ValueError as exc:
        caught = exc
    check("主异常不被自清吞掉", caught is not None and "被断言打挂" in str(caught))
    check("异常路径仍执行 cleanup", fx.cleanups == 1, f"cleanups={fx.cleanups}")


def t06_cleanup_failure_is_visible():
    fx = FakeFixture(health=False, cleanup_works=False)
    raised = None
    try:
        with managed(fx, verbose=False):
            pass
    except FixtureCleanupError as exc:
        raised = exc
    check("自清后复核仍健康 → 抛 FixtureCleanupError", raised is not None)
    check("自清失败文案点名 fixture", raised is not None and "fake" in str(raised), str(raised))


def t07_cleanup_error_chained_under_main_error():
    fx = FakeFixture(health=False, cleanup_works=False)
    raised = None
    try:
        with managed(fx, verbose=False):
            raise ValueError("主失败")
    except Exception as exc:  # noqa: BLE001
        raised = exc
    check("异常路径下以主异常为准", isinstance(raised, FixtureCleanupError), type(raised).__name__)
    check("cleanup 错误链上保留主异常", raised is not None
          and isinstance(raised.__context__, ValueError), str(raised and raised.__context__))


def t08_payload_is_exposed():
    fx = FakeFixture(health=False)
    fx.payload = "task-123"
    with managed(fx, verbose=False) as bound:
        check("managed 透出自建句柄", bound.payload == "task-123", str(bound.payload))


def main() -> int:
    print("S124 fixture 自愈框架自测（零外部资源）")
    for fn in (t01_healthy_needs_no_heal, t02_heal_exactly_once,
               t03_unavailable_raises_with_hint, t04_managed_cleans_up,
               t05_managed_cleans_up_on_exception, t06_cleanup_failure_is_visible,
               t07_cleanup_error_chained_under_main_error, t08_payload_is_exposed):
        print(f"[{fn.__name__}]")
        fn()
    print(f"\n合计：{PASS} PASS / {FAIL} FAIL")
    return 0 if FAIL == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
