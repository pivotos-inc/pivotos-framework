#!/usr/bin/env python3
"""
PivotOS S95 OCR 真库实测 E2E 自测脚本
链路：文件预签名直传 → 登记 → 建知识库 → 上传扫描图档 → Tika+OCR 解析 →
      分块 → Embedding → VectorStore → 文本块校验 → 相似性检索 → 清理
前置：后端带 OCR 装配启动（pivotos.kb.ocr.enabled=true + tessdata-path + languages=chi_sim+eng）
"""
import requests, json, time, sys, os

BASE = "http://localhost:8080"
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
# 支持命令行指定样图与关键词：python ocr_kb_e2e_test.py <图片路径> <关键词1,关键词2,...>
DEFAULT_IMAGE = os.path.normpath(os.path.join(SCRIPT_DIR, "..", "..", "..", "pivotos-tmp", "ocr-sample-s95.png"))
IMAGE_PATH = sys.argv[1] if len(sys.argv) > 1 else DEFAULT_IMAGE
FILE_NAME = os.path.basename(IMAGE_PATH)
KB_NAME = "s95-ocr-e2e"
# 样图内已知文案关键词（OCR 命中判定）
KEYWORDS = sys.argv[2].split(",") if len(sys.argv) > 2 else ["枢盘", "PivotOS", "迁移"]
SEARCH_QUERY = sys.argv[3] if len(sys.argv) > 3 else "旧系统迁移功能"

# ── 工具函数 ──────────────────────────────────────────────
def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)

def check(r, step):
    if r.status_code != 200:
        log("FAIL", f"{step} HTTP {r.status_code}: {r.text[:300]}")
        sys.exit(1)
    body = r.json()
    if not body.get("success") or body.get("code") != 0:
        log("FAIL", f"{step} 业务失败: {json.dumps(body, ensure_ascii=False)[:300]}")
        sys.exit(1)
    return body["data"]

if not os.path.exists(IMAGE_PATH):
    log("FAIL", f"样图不存在: {IMAGE_PATH}")
    sys.exit(1)

kb_id = doc_id = None

def cleanup():
    if doc_id:
        requests.delete(f"{BASE}/ai/kb/doc/{doc_id}", headers=HDR, timeout=10)
        log("CLEAN", f"已删除文档 {doc_id}")
    if kb_id:
        requests.delete(f"{BASE}/ai/kb/base/{kb_id}", headers=HDR, timeout=10)
        log("CLEAN", f"已删除知识库 {kb_id}")

# ── Step 1: 登录 ──────────────────────────────────────────
log("STEP1", "登录获取 token ...")
r = requests.post(f"{BASE}/system/auth/login",
                  json={"username": "admin", "password": "admin123"}, timeout=60)
token = check(r, "登录")["token"]
log("STEP1", f"token={token[:16]}...  OK")
HDR = {"Authorization": token}

# ── Step 2: 预签名直传样图 ────────────────────────────────
log("STEP2", "获取预签名上传地址 ...")
r = requests.get(f"{BASE}/file/presign", params={"filename": FILE_NAME},
                 headers=HDR, timeout=10)
presign = check(r, "预签名")
log("STEP2", f"objectKey={presign['objectKey']}  OK")

log("STEP2", "PUT 二进制直传 ...")
with open(IMAGE_PATH, "rb") as f:
    data = f.read()
r = requests.put(presign["uploadUrl"], data=data,
                 headers={"Content-Type": "image/png"}, timeout=60)
if r.status_code not in (200, 201, 204):
    log("FAIL", f"直传失败 HTTP {r.status_code}: {r.text[:200]}")
    sys.exit(1)
log("STEP2", f"直传 {len(data)} bytes  OK")

log("STEP2", "直传完成回调登记 ...")
r = requests.post(f"{BASE}/file/register", headers=HDR, timeout=10, json={
    "objectKey": presign["objectKey"], "originalName": FILE_NAME,
    "fileSize": len(data), "contentType": "image/png"})
file_id = check(r, "文件登记")
log("STEP2", f"sys_file id={file_id}  OK")

# ── Step 3: 创建知识库 ────────────────────────────────────
log("STEP3", f"创建知识库 {KB_NAME} ...")
r = requests.post(f"{BASE}/ai/kb/base", headers=HDR, timeout=10, json={
    "name": KB_NAME, "description": "S95 OCR 真库实测（自动化，测完即删）",
    "vectorStoreType": "milvus",
    "chunkSize": 500, "chunkOverlap": 100, "status": 1})
kb_id = check(r, "创建知识库")
log("STEP3", f"kbId={kb_id}  OK")

# ── Step 4: 上传文档触发向量化（OCR 在此环节介入） ────────
log("STEP4", "上传文档触发向量化 ...")
r = requests.post(f"{BASE}/ai/kb/doc/upload", headers=HDR, timeout=30, json={
    "kbId": kb_id, "fileName": FILE_NAME,
    "fileUrl": presign["objectKey"], "fileType": "image/png",
    "fileSize": len(data)})
doc_id = check(r, "上传文档")
log("STEP4", f"docId={doc_id}  OK")

# ── Step 5: 轮询向量化状态至 COMPLETED(2) ─────────────────
log("STEP5", "轮询向量化状态 ...")
STATUS = {0: "待处理", 1: "向量化中", 2: "已完成", 3: "失败"}
doc = None
for _ in range(60):  # 最多 10 分钟
    time.sleep(10)
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
    log("FAIL", "向量化超时（10 分钟）")
    cleanup()
    sys.exit(1)

# ── Step 6: 文本块校验（OCR 文本必须进入分块） ────────────
log("STEP6", "拉取文本块校验 OCR 命中 ...")
r = requests.get(f"{BASE}/ai/kb/doc/{doc_id}/chunks", headers=HDR, timeout=10)
chunks = check(r, "文本块列表")
all_text = "".join(c.get("content", "") for c in chunks)
log("STEP6", f"chunk 数={len(chunks)} 总字数={len(all_text)}")
hit = [k for k in KEYWORDS if k in all_text]
miss = [k for k in KEYWORDS if k not in all_text]
log("STEP6", f"关键词命中={hit} 未命中={miss}")
log("STEP6", f"分块文本预览: {all_text[:200]!r}")
if not hit:
    log("FAIL", "OCR 文本未进入分块（纯图片 Tika 无文本，命中为空即 OCR 未生效）")
    cleanup()
    sys.exit(1)

# ── Step 7: 相似性检索（RAG 召回验证） ────────────────────
log("STEP7", "相似性检索验证 RAG 召回 ...")
r = requests.post(f"{BASE}/ai/kb/base/search", headers=HDR, timeout=60, json={
    "kbId": kb_id, "query": SEARCH_QUERY, "topK": 3})
results = check(r, "相似性检索")
log("STEP7", f"召回 {len(results)} 条")
for i, res in enumerate(results):
    content = str(res.get("content", ""))[:100]
    log("STEP7", f"  [{i+1}] score={res.get('score')} content={content!r}")
if not results:
    log("FAIL", "检索无召回")
    cleanup()
    sys.exit(1)

# ── Step 8: 清理测试数据 ──────────────────────────────────
log("STEP8", "清理测试数据 ...")
cleanup()
kb_id = doc_id = None

log("PASS", f"S95 OCR 真库实测全链路通过：关键词命中 {hit}，检索召回 {len(results)} 条")
