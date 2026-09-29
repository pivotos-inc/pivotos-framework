#!/usr/bin/env python3
"""PivotOS OCR 真库实测 E2E 自测脚本（S115 fixture 复壮版）。

链路：文件预签名直传 → 登记 → 建知识库 → 上传扫描图档 → Tika+OCR 解析 →
      分块 → Embedding → VectorStore → 文本块校验 → 相似性检索 → 清理
前置：后端带 OCR 装配启动（pivotos.kb.ocr.enabled=true + tessdata-path + languages=chi_sim+eng）
      macOS brew 形态还需 JVM 参数 -Djna.library.path=/opt/homebrew/lib

S115 改动（三项 fixture 漂移的根因与处置）：
  K1 样图丢失：原默认样图 pivotos-tmp/ocr-sample-s95.png 是 S95（Windows 会话）时代产物，
     本机 pivotos-tmp/*.png 零命中。**pivotos-tmp 不在四仓任何 git 仓内**（本机私有目录），
     因此「重新落一张图」并不能根治——本版改为**脚本自生成**：文案固化在 ocr_sample.LINES，
     运行时用 Pillow 渲染到 pivotos-tmp/ocr-sample-s115.png，缺失/损坏自动重画。
  K2 向量库形态漂移：原脚本硬编码 ``vectorStoreType: milvus``（S95 时代 Windows dev 接云 Milvus）。
     本机 application-dev.yml 已改为 ``pivotos.kb.vector-store.type=simple``（Milvus 服务端未部署），
     建库不显式传对类型会走错装配。本版默认 simple，并支持命令行/环境变量覆盖。
  K3 环境不可得判定：后端若未带 OCR 启动（enabled=false）或 tessdata/chi_sim 缺失，
     属**环境不可得**，按 S115 开工口径改判 skip（exit=0）并打印修复姿势；
     环境就绪而 OCR 未产出则照常 FAIL（不掩盖真实缺陷）。

用法：python3 ocr_kb_e2e_test.py [样图路径] [关键词逗号分隔] [检索词] [向量库类型]
"""
import os
import re
import subprocess
import sys
import time

import requests
from ocr_sample import KEYWORDS_HINT, LINES, ensure_sample

BASE = "http://localhost:8080"
DEFAULT_IMAGE = ensure_sample()          # pivotos-tmp/ocr-sample-s115.png（缺即自生成）
IMAGE_PATH = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_IMAGE
FILE_NAME = os.path.basename(IMAGE_PATH)
KB_NAME = "s115-ocr-e2e"
KEYWORDS = sys.argv[2].split(",") if len(sys.argv) > 2 else KEYWORDS_HINT
SEARCH_QUERY = sys.argv[3] if len(sys.argv) > 3 else "旧系统迁移功能"
VECTOR_STORE = sys.argv[4] if len(sys.argv) > 4 else os.environ.get(
    "PIVOTOS_KB_VECTOR_STORE_TYPE", "simple")

_TESSDATA_CANDIDATES = (
    "/opt/homebrew/share/tessdata",
    "/usr/share/tesseract-ocr/5/tessdata",
    "/usr/share/tesseract-ocr/4/tessdata",
    "/usr/share/tessdata",
    "/usr/local/share/tessdata",
)


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def check(r, step):
    if r.status_code != 200:
        log("FAIL", f"{step} HTTP {r.status_code}: {r.text[:300]}")
        sys.exit(1)
    body = r.json()
    if not body.get("success") or body.get("code") != 0:
        log("FAIL", f"{step} 业务失败: {str(body)[:300]}")
        sys.exit(1)
    return body["data"]


def backend_args():
    """本机 8080 后端进程命令行（无法获取时返回 None，表示「不判定」）。"""
    try:
        out = subprocess.run(["lsof", "-Pan", "-i:8080", "-sTCP:LISTEN"],
                             capture_output=True, text=True, timeout=10).stdout
        pids = {ln.split()[1] for ln in out.splitlines()[1:] if len(ln.split()) > 1}
        for pid in pids:
            args = subprocess.run(["ps", "-o", "args=", "-p", pid],
                                  capture_output=True, text=True, timeout=10).stdout
            if "pivotos" in args:
                return args
    except Exception:  # noqa: BLE001 —— 非本机/命令缺失 → 不判定
        return None
    return None


def ocr_env_ready():
    """OCR 运行环境是否可得。返回 (bool, reason)。"""
    args = backend_args()
    if args is None:
        return True, "本机后端进程命令行不可读，按「可用」处理（失败即 FAIL）"
    if re.search(r"pivotos\.kb\.ocr\.enabled\s*=\s*false", args):
        return False, "后端显式以 pivotos.kb.ocr.enabled=false 启动"
    m = re.search(r"--pivotos\.kb\.ocr\.tessdata-path=(\S+)", args)
    if m and os.path.isdir(m.group(1)):
        return _has_lang(m.group(1))
    if m:
        return False, f"命令行指定的 tessdata 目录不存在：{m.group(1)}"
    for path in _TESSDATA_CANDIDATES:
        if os.path.isdir(path) and os.path.exists(os.path.join(path, "chi_sim.traineddata")):
            return True, f"tessdata={path}"
    return False, "未显式指定 tessdata-path，且本机未找到含 chi_sim 的 tessdata 目录"


def _has_lang(path):
    if not os.path.exists(os.path.join(path, "chi_sim.traineddata")):
        return False, f"tessdata 目录缺少 chi_sim.traineddata：{path}"
    return True, f"tessdata={path}"


if not IMAGE_PATH or not os.path.exists(IMAGE_PATH):
    log("SKIP", f"OCR 样图不可得（Pillow 缺失或无法生成）：{IMAGE_PATH}")
    sys.exit(0)

ok, reason = ocr_env_ready()
log("ENV", f"OCR 环境: {reason}")
if not ok:
    log("SKIP", "OCR 链路在 dev 环境不可得（属①环境不可得口径，非产品缺陷），改判 skip。\n"
                "        带 OCR 启动姿势（macOS brew）：\n"
                "        java -Djna.library.path=/opt/homebrew/lib -jar pivotos-admin-server.jar \\\n"
                "          --pivotos.kb.ocr.enabled=true \\\n"
                "          --pivotos.kb.ocr.tessdata-path=/opt/homebrew/share/tessdata \\\n"
                "          --pivotos.kb.ocr.languages=chi_sim+eng")
    sys.exit(0)

kb_id = doc_id = None


def cleanup():
    if doc_id:
        requests.delete(f"{BASE}/ai/kb/doc/{doc_id}", headers=HDR, timeout=10)
        log("CLEAN", f"已删除文档 {doc_id}")
    if kb_id:
        requests.delete(f"{BASE}/ai/kb/base/{kb_id}", headers=HDR, timeout=10)
        log("CLEAN", f"已删除知识库 {kb_id}")


# ── Step 1: 登录 ──────────────────────────────────────────────
log("STEP1", "登录获取 token ...")
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
token = check(r, "登录")["token"]
HDR = {"Authorization": token}
log("STEP1", f"token={token[:16]}...  OK")

# ── Step 2: 预签名直传样图 ─────────────────────────────────────
log("STEP2", f"预签名直传样图 {IMAGE_PATH} ...")
r = requests.get(f"{BASE}/file/presign", params={"filename": FILE_NAME}, headers=HDR, timeout=10)
presign = check(r, "预签名")
with open(IMAGE_PATH, "rb") as f:
    data = f.read()
r = requests.put(presign["uploadUrl"], data=data,
                 headers={"Content-Type": "image/png"}, timeout=60)
if r.status_code not in (200, 201, 204):
    log("FAIL", f"直传失败 HTTP {r.status_code}: {r.text[:200]}")
    sys.exit(1)
r = requests.post(f"{BASE}/file/register", headers=HDR, timeout=10, json={
    "objectKey": presign["objectKey"], "originalName": FILE_NAME,
    "fileSize": len(data), "contentType": "image/png"})
file_id = check(r, "文件登记")
log("STEP2", f"直传 {len(data)} bytes，sys_file id={file_id}  OK")

# ── Step 3: 创建知识库 ────────────────────────────────────────
log("STEP3", f"创建知识库 {KB_NAME}（vectorStoreType={VECTOR_STORE}）...")
r = requests.post(f"{BASE}/ai/kb/base", headers=HDR, timeout=10, json={
    "name": KB_NAME, "description": "S115 OCR fixture 复壮（自动化，测完即删）",
    "vectorStoreType": VECTOR_STORE,
    "chunkSize": 500, "chunkOverlap": 100, "status": 1})
kb_id = check(r, "创建知识库")
log("STEP3", f"kbId={kb_id}  OK")

# ── Step 4: 上传文档触发向量化（OCR 在此环节介入）─────────────
log("STEP4", "上传文档触发向量化 ...")
r = requests.post(f"{BASE}/ai/kb/doc/upload", headers=HDR, timeout=30, json={
    "kbId": kb_id, "fileName": FILE_NAME,
    "fileUrl": presign["objectKey"], "fileType": "image/png",
    "fileSize": len(data)})
doc_id = check(r, "上传文档")
log("STEP4", f"docId={doc_id}  OK")

# ── Step 5: 轮询向量化状态至 COMPLETED(2) ─────────────────────
log("STEP5", "轮询向量化状态 ...")
STATUS = {0: "待处理", 1: "向量化中", 2: "已完成", 3: "失败"}
for _ in range(60):
    time.sleep(5)
    r = requests.get(f"{BASE}/ai/kb/doc/{doc_id}", headers=HDR, timeout=10)
    doc = check(r, "查询文档")
    s = doc["status"]
    log("STEP5", f"status={STATUS.get(s, s)} chunkCount={doc.get('chunkCount')}")
    if s == 2:
        break
    if s == 3:
        log("FAIL", f"向量化失败: {doc.get('errorMsg')}")
        cleanup()
        sys.exit(1)
else:
    log("FAIL", "向量化超时（5 分钟）")
    cleanup()
    sys.exit(1)

# ── Step 6: 文本块校验（OCR 文本必须进入分块）────────────────
log("STEP6", "拉取文本块校验 OCR 命中 ...")
r = requests.get(f"{BASE}/ai/kb/doc/{doc_id}/chunks", headers=HDR, timeout=10)
chunks = check(r, "文本块列表")
all_text = "".join(c.get("content", "") for c in chunks)
hit = [k for k in KEYWORDS if k in all_text]
miss = [k for k in KEYWORDS if k not in all_text]
log("STEP6", f"chunk 数={len(chunks)} 总字数={len(all_text)}")
log("STEP6", f"关键词命中={hit} 未命中={miss}")
log("STEP6", f"分块文本预览: {all_text[:200]!r}")
if not hit:
    log("FAIL", "OCR 文本未进入分块（纯图片 Tika 无文本，命中为空即 OCR 未生效）")
    cleanup()
    sys.exit(1)

# ── Step 7: 相似性检索（RAG 召回验证）────────────────────────
log("STEP7", "相似性检索验证 RAG 召回 ...")
r = requests.post(f"{BASE}/ai/kb/base/search", headers=HDR, timeout=60, json={
    "kbId": kb_id, "query": SEARCH_QUERY, "topK": 3})
results = check(r, "相似性检索")
log("STEP7", f"检索词={SEARCH_QUERY}  召回 {len(results)} 条")
for i, res in enumerate(results):
    log("STEP7", f"  [{i + 1}] score={res.get('score')} content={str(res.get('content', ''))[:100]!r}")
if not results:
    log("FAIL", "检索无召回")
    cleanup()
    sys.exit(1)

# ── Step 8: 清理测试数据 ─────────────────────────────────────
log("STEP8", "清理测试数据 ...")
cleanup()
kb_id = doc_id = None

log("PASS", f"OCR 真库实测全链路通过：样图={FILE_NAME}（脚本自生成），"
            f"关键词命中 {hit}，检索召回 {len(results)} 条")
