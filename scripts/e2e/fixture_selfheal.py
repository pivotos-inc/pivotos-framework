#!/usr/bin/env python3
"""S124 · 公共 fixture 自愈框架（E2E 资产）。

背景（S108 K2 / S114 §5.1 / S115 fixture 复壮）：
    E2E 反复出现「fixture 漂移」型 FAIL——迁移任务 DB 行丢失、OCR 样图丢失、
    前提结构变更。S115 已经把这两个 fixture 改成「脚本自建 + 跑完自清」，
    但**自愈这件事本身没有被抽象**：每个脚本各写各的 probe/heal/cleanup，
    顺序、重试、归零复核全靠人肉一致，下次新增 fixture 大概率再漂一次。

本模块把自愈流程固化成一条契约：

    probe() 判健康 → 不健康才 heal()（且只 heal 一次，不无限重试）
    → 仍不健康抛 FixtureUnavailable（带上「启动姿势」hint，禁止静默 skip）
    → managed() 上下文退出必 cleanup()，且 cleanup 后**复核 probe 为 False**
      （历史教训：自清静默失败 = 脚本绿着退出、dev 库却越攒越脏）

约束：
    · 只依赖 Python 标准库（E2E 资产不许引入第三方依赖）；
    · heal 只调一次是有意为之——fixture 建不出来通常是环境问题（服务没起、
      凭证缺失），重试一百次也只是把脚本挂住 100 倍时长；
    · cleanup 必须返回「清除条数」，0 也要能被复核步骤看见（不能拿 None 蒙混）。

用法：
    from fixture_selfheal import managed, OcrSampleFixture
    with managed(OcrSampleFixture()) as fx:
        ...  # 用 fx.payload 拿自建出来的资源句柄
"""
from __future__ import annotations

import os
from contextlib import contextmanager


class FixtureError(RuntimeError):
    """fixture 基类异常。"""


class FixtureUnavailable(FixtureError):
    """probe 失败且 heal 后仍不健康——环境不可得，必须显式暴露而不是静默 skip。"""


class FixtureCleanupError(FixtureError):
    """自清失败（含「清完复核仍不为空」）——这类静默失败会让 dev 库越攒越脏。"""


class Fixture:
    """fixture 协议。子类实现四个动作，流程由本模块统一驱动。"""

    name = "fixture"

    def probe(self) -> bool:
        """当前是否健康（资源已存在且可用）。必须为纯查询，不能有副作用。"""
        raise NotImplementedError

    def heal(self) -> None:
        """自建资源。probe 为 False 时被调用，全框架只调一次。"""
        raise NotImplementedError

    def cleanup(self) -> int:
        """清除自建资源，返回清除条数（0 表示本就没有可清的）。"""
        raise NotImplementedError

    def hint(self) -> str:
        """不可得时的处置提示（启动命令 / 环境变量 / 前置条件）。"""
        return "未给出处置提示"

    # 便捷：给 managed() 塞自建出来的句柄（如 task_id、文件路径）
    payload = None


# ------------------------------------------------------------------ 核心流程

def ensure(fx: Fixture, verbose: bool = True) -> bool:
    """probe →（不健康才 heal，且仅一次）→ 复核。

    返回 True 表示可用；不可用时抛 :class:`FixtureUnavailable`（不静默 skip）。
    """
    if fx.probe():
        if verbose:
            print(f"[fixture] {fx.name}: 已就绪（无需自建）")
        return True

    print(f"[fixture] {fx.name}: 探测不健康，开始自建")
    fx.heal()

    if fx.probe():
        print(f"[fixture] {fx.name}: 自建成功")
        return True

    raise FixtureUnavailable(
        f"fixture 不可得：{fx.name}\n  处置提示：{fx.hint()}")


@contextmanager
def managed(fx: Fixture, verbose: bool = True):
    """ensure → yield → cleanup → 复核归零。

    cleanup 在正常与异常路径都会执行；cleanup 内部抛错时：
      · 正常路径 → 直接抛 FixtureCleanupError；
      · 异常路径 → 以主异常为准，cleanup 错误作为 __context__ 链上抛出，
        避免「自清失败」盖掉真正的测试失败原因。
    """
    ensure(fx, verbose=verbose)
    try:
        yield fx
    except BaseException as exc:  # noqa: BLE001 - 必须覆盖 KeyboardInterrupt
        try:
            _cleanup_and_verify(fx, verbose)
        except FixtureCleanupError as clean_err:
            raise clean_err from exc
        raise
    _cleanup_and_verify(fx, verbose)


def _cleanup_and_verify(fx: Fixture, verbose: bool = True) -> None:
    removed = fx.cleanup()
    if verbose:
        print(f"[fixture] {fx.name}: 自清完成，清除 {removed} 项")
    # 复核归零：清完必须回到「不健康」（即资源真的没了）
    if fx.probe():
        raise FixtureCleanupError(
            f"fixture 自清后复核仍为健康：{fx.name}（疑似只清了一半，"
            f"dev 库/磁盘会持续攒脏数据）")


# ------------------------------------------------------------------ 具体 fixture

class OcrSampleFixture(Fixture):
    """OCR 样图：由常量文案即时渲染（S115 口径，不落仓库外依赖）。"""

    name = "ocr-sample"

    def __init__(self, path: str | None = None):
        self.path = path

    def probe(self) -> bool:
        return os.path.exists(self._path())

    def heal(self) -> None:
        try:
            import ocr_sample
        except ImportError as exc:  # pragma: no cover - 路径异常才触发
            raise FixtureUnavailable(f"ocr_sample 模块不可导入：{exc}") from exc
        self.payload = ocr_sample.ensure_sample(self._path()) or self._path()

    def cleanup(self) -> int:
        p = self._path()
        if os.path.exists(p):
            os.remove(p)
            return 1
        return 0

    def hint(self) -> str:
        return ("样图应由脚本自渲染（ocr_sample.render），渲染失败通常是缺 Pillow 或字体；"
                "处置：python3 -m pip install pillow，或检查 ocr_sample.FONT_CANDIDATES 在本机是否存在")

    def _path(self) -> str:
        if self.path:
            return self.path
        import ocr_sample
        return ocr_sample.DEFAULT_OUT


class MigrationTaskFixture(Fixture):
    """迁移任务：自建任务 → 上传 → 解析 →（可选）分析/计划 → 跑完物理自清。

    需要后端 8080 已启动（与全部 migration E2E 同前提）。
    """

    name = "migration-task"

    def __init__(self, upto: str = "plan", prefix: str = "S124"):
        self.upto = upto
        self.prefix = prefix
        self.task_id = None

    def probe(self) -> bool:
        return self.task_id is not None

    def heal(self) -> None:
        try:
            import migration_fixture as mf
        except ImportError as exc:  # pragma: no cover
            raise FixtureUnavailable(f"migration_fixture 模块不可导入：{exc}") from exc
        hdr = mf.login()
        self.task_id = mf.bootstrap(hdr, mf.run_name(self.prefix), upto=self.upto)
        self.payload = self.task_id

    def cleanup(self) -> int:
        if self.task_id is None:
            return 0
        import migration_fixture as mf
        n = mf.cleanup(self.task_id)
        self.task_id = None
        return n

    def hint(self) -> str:
        return ("迁移任务需后端在 8080 就绪：env TESSDATA_PREFIX=/opt/homebrew/share/tessdata "
                "java -Djna.library.path=/opt/homebrew/lib -Djava.awt.headless=true "
                "-jar pivotos-admin-server/target/pivotos-admin-server.jar --spring.profiles.active=dev；"
                "且 dev 库 ai_api_key 需可解析出静态 Key（见 migration_fixture.ensure_ai_key）")
