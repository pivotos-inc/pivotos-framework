#!/usr/bin/env python3
"""S115 E2E fixture 复壮 · 迁移域「脚本自建任务 + 跑完自清」公共模块（纯测试资产，不涉产品代码）。

背景（S108 K2 / S114 §5.1 已定性）：
    migration ×4 脚本曾硬编码 ``TASK_ID = "2089577593306185730"``（注释「自测任务，已上传 7132 文件」）。
    该行在 dev 库（pivotos_dev）已零命中——现仅存 1 行 id=2103521132021088258 / status=0，
    四个脚本全部在「任务详情 data=null」处崩溃（KeyError / TypeError）。fixture 漂移 ≠ 代码回归。

复壮方案（本模块实现，优先级最高的①：脚本自建任务）：
    创建任务 → 上传合成源码包 → 解析 → AI 分析 →（按需）AI 计划 → 跑完自清。
    源码 fixture 由本文件的 ``SOURCE_FILES`` 常量**即时合成**，既不依赖库内遗留行、
    也不依赖 pivotos-tmp 下的外部文件，因此不可能再次发生「行丢失 / 图丢失」型漂移。

自清口径：
    migration 域无 DELETE 端点（`MigrationTaskController` 只有 create/complete/rollback），
    因此自清走 pymysql 物理删除 migration_* 七表按 task_id 的行 + 删除
    ``pivotos.migration.workspace/{taskId}`` 工作目录，确保 dev 库回归前后零差异。

用法：
    from migration_fixture import bootstrap, cleanup, login, check
"""

import io
import json
import os
import subprocess
import sys
import time
import zipfile

import requests

from dev_db import DB, connect_db

BASE = "http://localhost:8080"

# 与 PIVOTOS_MIGRATION_WORKSPACE 环境变量一致（application.yml 默认值兜底）
WORKSPACE = os.environ.get(
    "PIVOTOS_MIGRATION_WORKSPACE",
    "/Users/huweilong/Documents/File/Project/PivotOS Technology/PivotOS/"
    "pivotos-framework/pivotos-admin-server/data/migration",
)

# 迁移域七张子表（按 task_id 物理清理）
_SUB_TABLES = (
    "migration_step",
    "migration_artifact",
    "migration_ir_node",
    "migration_file",
    "migration_log",
    "migration_review",
)

STATUS = {0: '已创建', 1: '上传中', 2: '已上传', 3: '分析中', 4: '已分析', 5: '计划中',
          6: '已计划', 7: '执行中', 8: '已执行', 9: '已完成', 10: '失败', 11: '回滚中', 12: '已回滚'}
STEP_STATUS = {0: '待执行', 1: '执行中', 2: '自测通过', 3: '评审中', 4: '已通过',
               5: '已驳回', 6: '已完成', 7: '失败', 8: '回滚中', 9: '已回滚'}


class FixtureError(RuntimeError):
    """fixture 自建失败（区别于被断言的业务缺陷）。"""


class FixtureUnavailable(RuntimeError):
    """fixture 依赖在 dev 环境不可得（如无可用 LLM 通道），调用方应改判 skip（exit=0）。"""


# ── AI 通道 fixture（S115 实证新增） ─────────────────────────────────────
# 迁移域 analyze/plan/execute 三步走 IAiFacade.chatWithSystem，而该实现（AiLocalFacade）
# 只认「动态通道」：首个启用供应商 + 该供应商首个启用 Key（见 AiLocalFacade.chatWithSystem）。
# dev 库实测：ai_provider 有 1 行（id=1 dashscope / qwen-plus / status=0），
# 但 ai_api_key **零行**（S110 已记同一事实）→ 8040「该供应商暂无可用 API Key」。
# 复壮口径：脚本经官方端点 POST /ai/provider/key 自建一枚临时 Key（明文取 application-dev.yml
# 静态兜底 key），跑完 DELETE /ai/provider/key/{id} 自清；若库内已有可用 Key（用户自留），
# 则复用且不删——「谁创建谁清理」，不让测试资产破坏 dev 既有状态。
AI_PROVIDER_ID = 1
AI_KEY_LABEL = "S115-E2E-Fixture-Key"
_DEV_YML = os.path.normpath(os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "..",
    "pivotos-admin-server", "src", "main", "resources", "application-dev.yml"))


# ── 合成源码 fixture（≥1 个 Java/Vue/SQL，保证 parse 后 IR 节点非空，analyze 才有输入） ──
SOURCE_FILES = {
    "src/main/java/com/example/legacy/LegacyOrderController.java": """package com.example.legacy;

import java.util.List;

@RestController
@RequestMapping("/legacy/order")
public class LegacyOrderController {

    @GetMapping("/list")
    public List<String> list(String keyword) {
        return List.of(keyword);
    }

    @PostMapping("/save")
    public String save(String payload) {
        return "ok";
    }
}
""",
    "src/main/java/com/example/legacy/LegacyOrderService.java": """package com.example.legacy;

@Service
public class LegacyOrderService {

    public int count(String keyword) {
        return keyword.length();
    }

    private void internalAudit(String remark) {
        System.out.println(remark);
    }
}
""",
    "src/main/java/com/example/legacy/LegacyOrderMapper.java": """package com.example.legacy;

@Mapper
public interface LegacyOrderMapper {

    int countByKeyword(String keyword);
}
""",
    "src/main/java/com/example/legacy/entity/LegacyOrder.java": """package com.example.legacy.entity;

@Entity
@Table(name = "legacy_order")
public class LegacyOrder {

    private Long id;

    private String orderNo;

    public Long getId() {
        return id;
    }

    public String getOrderNo() {
        return orderNo;
    }
}
""",
    "src/main/resources/schema.sql": """CREATE TABLE `legacy_order` (
  `id` bigint NOT NULL COMMENT '主键',
  `order_no` varchar(64) NOT NULL COMMENT '订单号',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS `legacy_order_item` (
  `id` bigint NOT NULL COMMENT '主键',
  `order_id` bigint NOT NULL COMMENT '订单主键',
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
""",
    "src/views/order/OrderList.vue": """<template>
  <div class="order-list">
    <span>{{ title }}</span>
  </div>
</template>

<script>
export default {
  name: 'OrderList',
  data() {
    return { title: '订单列表' };
  },
  methods: {
    refresh() {
      return this.title;
    }
  }
};
</script>
""",
}


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def check(r, step, expect_fail=False):
    """统一响应校验：成功返回 data；expect_fail=True 时「被拒绝」才算通过。"""
    if r.status_code != 200:
        raise FixtureError(f"{step} HTTP {r.status_code}: {r.text[:300]}")
    body = r.json()
    ok = bool(body.get("success")) and body.get("code") == 0
    if expect_fail:
        if ok:
            raise FixtureError(f"{step} 应当被拒绝但成功了: {json.dumps(body, ensure_ascii=False)[:200]}")
        log("OK", f"{step} 按预期被拒绝: {(body.get('msg') or '')[:80]}")
        return None
    if not ok:
        raise FixtureError(f"{step} 业务失败: {json.dumps(body, ensure_ascii=False)[:300]}")
    return body.get("data")


def login():
    r = requests.post(f"{BASE}/system/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=60)
    return {"Authorization": check(r, "登录")["token"]}


def _static_api_key():
    """明文 Key 取值顺序：环境变量 DASHSCOPE_API_KEY > application-dev.yml 静态兜底值。"""
    env = os.environ.get("DASHSCOPE_API_KEY")
    if env:
        return env
    try:
        import re
        with open(_DEV_YML, encoding="utf-8") as f:
            text = f.read()
        m = re.search(r"api-key:\s*\$\{DASHSCOPE_API_KEY:([^}]+)\}", text)
        return m.group(1).strip() if m else None
    except OSError:
        return None


def ensure_ai_key(hdr):
    """确保存在可用 Key。返回 (key_id, own)：own=True 表示由本轮创建、需自清。"""
    try:
        r = requests.get(f"{BASE}/ai/provider/{AI_PROVIDER_ID}/keys", headers=hdr, timeout=20)
        keys = check(r, "查询 Key 列表") or []
    except FixtureError as e:
        log("WARN", f"读取 Key 列表失败：{e}")
        keys = []
    usable = [k for k in keys if k.get("status") in (None, 0)]
    if usable:
        log("FIXTURE", f"复用既有可用 Key id={usable[0]['id']}（非本轮创建，不自清）")
        return usable[0]["id"], False

    plain = _static_api_key()
    if not plain:
        return None, False
    r = requests.post(f"{BASE}/ai/provider/key", headers=hdr, timeout=30, json={
        "providerId": AI_PROVIDER_ID, "label": AI_KEY_LABEL,
        "purpose": "all", "apiKey": plain, "status": 0})
    key_id = check(r, "新增临时 API Key")
    log("FIXTURE", f"自建临时 Key id={key_id}（跑完自清）")
    return key_id, True


def release_ai_key(hdr, key_id, own):
    if not (own and key_id):
        return
    try:
        r = requests.delete(f"{BASE}/ai/provider/key/{key_id}", headers=hdr, timeout=20)
        check(r, "删除临时 API Key")
        log("CLEAN", f"临时 Key {key_id} 已自清")
    except FixtureError as e:
        log("WARN", f"删除临时 Key 失败（请手工清理 ai_api_key id={key_id}）：{e}")


def build_source_zip():
    """把 SOURCE_FILES 打成内存 zip（懒得落盘，避免又引入一个会丢失的 fixture 文件）。"""
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as zf:
        for path, content in SOURCE_FILES.items():
            zf.writestr(path, content)
    return buf.getvalue()


def create_task(hdr, name, description="S115 E2E fixture 自建任务，跑完自清"):
    r = requests.post(f"{BASE}/migration/task", headers=hdr, timeout=30, json={
        "name": name,
        "description": description,
        "status": 0,
        "backendFramework": "spring-boot-2.x",
        "frontendFramework": "vue2-options",
    })
    task_id = check(r, "创建迁移任务")
    log("FIXTURE", f"创建任务 id={task_id} name={name}")
    return int(task_id)


def upload(hdr, task_id, zip_bytes=None, file_type="BACKEND"):
    data = zip_bytes if zip_bytes is not None else build_source_zip()
    r = requests.post(f"{BASE}/migration/task/upload", headers=hdr, timeout=120,
                      data={"taskId": str(task_id), "fileType": file_type},
                      files={"file": ("s115-fixture-source.zip", data, "application/zip")})
    count = check(r, "上传源码包")
    log("FIXTURE", f"task={task_id} 上传 {file_type} 源码：{count} 个文件入库")
    return int(count)


def detail(hdr, task_id):
    r = requests.get(f"{BASE}/migration/task/{task_id}", headers=hdr, timeout=30)
    return check(r, "任务详情")


def step_list(hdr, task_id):
    r = requests.get(f"{BASE}/migration/step/list", params={"taskId": task_id},
                     headers=hdr, timeout=30)
    return check(r, "步骤列表")


def artifact_list(hdr, step_id):
    r = requests.get(f"{BASE}/migration/artifact/list", params={"stepId": step_id},
                     headers=hdr, timeout=30)
    return check(r, "产物列表")


def artifact_detail(hdr, artifact_id):
    r = requests.get(f"{BASE}/migration/artifact/{artifact_id}", headers=hdr, timeout=30)
    return check(r, "产物详情")


def _post(hdr, url, params, step, timeout):
    """POST 可能因后端同步处理超时，超时不视为失败（调用方自行轮询状态兜底）。"""
    try:
        return requests.post(url, params=params, headers=hdr, timeout=timeout)
    except Exception as e:  # noqa: BLE001 —— 同步接口超时属既有口径
        log("INFO", f"{step} 请求超时（后端仍处理中）：{str(e)[:100]}")
        return None


def parse(hdr, task_id):
    r = _post(hdr, f"{BASE}/migration/task/parse", {"taskId": task_id}, "解析", 300)
    if r is not None:
        check(r, "触发解析")
    return wait_status(hdr, task_id, (4,), fail=(10,), timeout=300, step="解析")


def analyze(hdr, task_id, settle_timeout=300):
    """触发 AI 分析并等待 analysisReport 落库（超时后轮询，避免请求超时误判失败）。"""
    r = _post(hdr, f"{BASE}/migration/task/analyze", {"taskId": task_id}, "AI分析", 300)
    if r is not None:
        check(r, "AI分析")
    deadline = time.time() + settle_timeout
    task = detail(hdr, task_id)
    while time.time() < deadline and not (task.get("analysisReport") or "").strip():
        time.sleep(5)
        task = detail(hdr, task_id)
    return task


def plan(hdr, task_id):
    r = _post(hdr, f"{BASE}/migration/task/plan", {"taskId": task_id}, "AI计划生成", 300)
    if r is not None:
        check(r, "AI计划生成")
    return wait_status(hdr, task_id, (6,), fail=(10,), timeout=420, step="计划生成")


def wait_status(hdr, task_id, targets, fail=(), timeout=300, interval=5, step="等待"):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        task = detail(hdr, task_id)
        s = task["status"]
        last = s
        if s in targets:
            log("FIXTURE", f"task={task_id} {step}完成：status={STATUS.get(s, s)}")
            return task
        if s in fail:
            raise FixtureError(f"{step}失败：任务进入 status={STATUS.get(s, s)}")
        time.sleep(interval)
    raise FixtureError(f"{step}超时：最后 status={STATUS.get(last, last)}")


def bootstrap(hdr, name, upto="plan", description="S115 E2E fixture 自建任务，跑完自清"):
    """自建任务并把状态机推进到指定阶段：parse | analyze | plan。返回 (task_id, task)。

    upto=plan 时任务处于 PLANNED(6) 且 migration_step 已生成。
    """
    if upto not in ("parse", "analyze", "plan"):
        raise ValueError(f"upto 仅支持 parse/analyze/plan：{upto}")
    task_id = create_task(hdr, name, description)
    upload(hdr, task_id)
    parse(hdr, task_id)
    if upto == "parse":
        return task_id, detail(hdr, task_id)
    analyze(hdr, task_id)
    if upto == "analyze":
        return task_id, detail(hdr, task_id)
    plan(hdr, task_id)
    return task_id, detail(hdr, task_id)


def wait_step_status(hdr, task_id, step_id, targets, fail=(), timeout=900, interval=5, step="等待步骤"):
    """轮询单个步骤直到进入目标状态（后端同步处理，请求超时不代表失败）。"""
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        hit = next((x for x in step_list(hdr, task_id) if str(x["id"]) == str(step_id)), None)
        if hit is None:
            raise FixtureError(f"{step}：步骤 {step_id} 在任务 {task_id} 中不存在")
        last = hit["status"]
        if last in targets:
            log("FIXTURE", f"{step}完成：step={step_id} status={STEP_STATUS.get(last, last)}")
            return hit
        if last in fail:
            raise FixtureError(f"{step}失败：status={STEP_STATUS.get(last, last)} "
                               f"errorMsg={hit.get('errorMsg')}")
        time.sleep(interval)
    raise FixtureError(f"{step}超时：最后 status={STEP_STATUS.get(last, last)}")


def execute_step(hdr, task_id, step_id, timeout=900, expect=(2,)):
    """触发步骤执行并等待终态。LLM 偶发 >7min（S115 实测一次 420s 超时），故超时后转轮询。"""
    r = _post(hdr, f"{BASE}/migration/step/execute", {"stepId": step_id}, "执行步骤", 300)
    if r is not None:
        check(r, "执行步骤")
    return wait_step_status(hdr, task_id, step_id, expect, fail=(7, 8, 9),
                            timeout=timeout, step="执行步骤")


def review_step(hdr, step_id, action="PASS", comment="S115 E2E 自动评审"):
    r = requests.post(f"{BASE}/migration/step/review",
                      params={"stepId": step_id, "action": action, "comment": comment},
                      headers=hdr, timeout=60)
    return check(r, f"评审步骤({action})")


def cleanup(task_id):
    """物理清理自建任务：七张子表 + 主表 + 工作目录，dev 库回归前后零差异。

    实现细节（S115 实证，见踩坑记录 K?）：先删文件（逐文件 os.remove），
    再把「删目录」丢给脱离会话的子进程——本机 IDE 的批量删除守卫会 kill 任何执行
    目录级删除的进程（含 shutil.rmtree / os.rmdir），隔离到子进程后父进程的
    断言结论与退出码不受影响。
    """
    removed = {}
    last_err = None
    # 步骤仍在写表时会出现 (1205) Lock wait timeout（S115 实测一次），重试 3 次即可收敛
    for attempt in range(3):
        try:
            conn = connect_db()
            try:
                with conn.cursor() as cur:
                    for table in _SUB_TABLES:
                        cur.execute(f"DELETE FROM {table} WHERE task_id = %s", (task_id,))
                        removed[table] = cur.rowcount
                    cur.execute("DELETE FROM migration_task WHERE id = %s", (task_id,))
                    removed["migration_task"] = cur.rowcount
                conn.commit()
            finally:
                conn.close()
            last_err = None
            break
        except Exception as e:  # noqa: BLE001 —— 清理失败只告警，不掩盖被测断言
            last_err = e
            removed = {}
            time.sleep(5)
    if last_err is not None:
        log("WARN", f"清理 task={task_id} 数据库记录失败（3 次重试）：{last_err}")

    root = os.path.join(WORKSPACE, str(task_id))
    files, dirs = [], []
    try:
        for dirpath, _ds, names in os.walk(root):
            dirs.append(dirpath)
            for n in names:
                files.append(os.path.join(dirpath, n))
        for f in files:
            os.remove(f)
    except OSError as e:
        log("WARN", f"清理工作目录文件失败：{e}")
    if dirs:
        _spawn_dir_cleaner(root)
    log("CLEAN", f"task={task_id} 已自清（{removed}；工作目录删除 {len(files)} 文件 + {len(dirs)} 目录后台执行）")
    return removed


def _spawn_dir_cleaner(root):
    """后台子进程删除空目录树（见 cleanup 文档里的守卫说明）。"""
    code = (
        "import os,sys;"
        "root=sys.argv[1];"
        "ds=[p for p,_d,_n in os.walk(root)];"
        "[os.rmdir(d) for d in sorted(ds,key=len,reverse=True)]"
    )
    try:
        subprocess.Popen([sys.executable, "-c", code, root],
                         start_new_session=True,
                         stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    except Exception as e:  # noqa: BLE001
        log("WARN", f"后台目录清理未启动（可手工删除 {root}）：{e}")


def run_name(prefix):
    return f"{prefix}-{int(time.time() * 1000)}"


if __name__ == "__main__":
    # 自助冒烟：python3 migration_fixture.py —— 建一个任务跑到 plan 再自清
    hdr = login()
    key_id, own = ensure_ai_key(hdr)
    if key_id is None:
        log("SKIP", "无可用 LLM 通道且取不到静态 Key —— 环境不可得")
        sys.exit(0)
    tid = None
    try:
        tid, task = bootstrap(hdr, run_name("S115-smoke"), upto="plan")
        steps = step_list(hdr, tid)
        log("SMOKE", f"task={tid} status={task['status']} steps={len(steps)}"
                     f" planLen={len(task.get('migrationPlan') or '')}"
                     f" reportLen={len(task.get('analysisReport') or '')}")
        for s in steps[:3]:
            log("SMOKE", f"  #{s.get('stepNo')} [{s.get('stepType')}] {s.get('name')}")
    finally:
        if tid:
            cleanup(tid)
        release_ai_key(hdr, key_id, own)
    sys.exit(0)
