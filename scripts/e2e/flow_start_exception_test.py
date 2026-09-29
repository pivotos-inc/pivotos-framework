#!/usr/bin/env python3
"""L2 清偿复验（网关位于审批节点之后的「新定义形态」）——S115 fixture 复壮版。

原脚本背景（S93）：
    排他网关条件全不匹配时发起流程，引擎抛 FlowException，服务层未翻译 → 1500「系统内部错误」。
    L2 清偿：``FlowInstanceService.start()`` catch FlowException → ServiceException 友好文案。

fixture 漂移（S103~S105 迭代，S108 K2 / S114 §5.1 已定性）：
    dev 库 fixture（``bpmn_s103_veto`` / ``leave_tier_s103``）迭代到网关**移到 apply 之后**的新形态，
    「网关紧跟开始、无变量发起即失败」的前提失效 —— 原脚本断言
    「无变量发起必须失败」因此长期 FAIL（属于 fixture 漂移，非引擎回归）。

本版实证并改写到的形态（2026-09-29 探针 ``pivotos-tmp/s115/probe_flow_start.py`` 实测）：
    ① 无变量发起 bpmn_s103_veto v8 → **成功**，实例落在「提交申请」节点；
    ② 推进 apply 才触发网关条件求值 → ``code=1500 msg=审批通过失败：跳转条件不能为空!``
       —— **L2 翻译守卫在新触发点仍然生效**（友好业务文案，非「系统内部错误」）。

复壮方案：不再依赖 dev 库存量 fixture（它会随别人迭代再次漂移），改为沿用
``s108_l2_gateway_first_test`` 的「**一次性自建定义 → 复验 → 自清不留 fixture**」口径，
一次性建出「开始 → apply → 网关 → 分支审批 → 结束」的**新定义形态**并断言新语义：
    A. 无变量发起**成功**（网关不在发起路径上）+ 推进 apply 时网关拒绝并返回业务文案；
    B. 带 days=5 发起 → 推进 apply → gt 分支路由正确（正常链路未被破坏）。
与 s108（断言「网关紧跟开始，发起即失败」）形成**互补**：同一 L2 守卫的两个触发点各有一案，
不存在覆盖重复，也不需要为让它变绿而改产品代码。
"""
import json
import sys

import requests
from dev_db import purge_flow_definition, purge_flow_instances

BASE = "http://localhost:8080"
FLOW_CODE = "s115_gw_after_apply"


def log(tag, msg):
    print(f"[{tag}]", msg, flush=True)


def check(r, step):
    if r.status_code != 200:
        raise AssertionError(f"{step} HTTP {r.status_code}: {r.text[:300]}")
    return r.json()


def gw_after_apply_defjson():
    """开始 → 提交申请(apply) → 互斥网关(le/gt@@days|3) → 主管/负责人 → 结束。"""
    def node(code, name, ntype, perm=None, coord="0,0", skips=()):
        n = {"nodeType": ntype, "nodeCode": code, "nodeName": name,
             "nodeRatio": "0.000", "coordinate": coord,
             "skipList": [dict(s) for s in skips]}
        if perm is not None:
            n["permissionFlag"] = perm
        return n

    def skip(now, nxt, name, cond=None):
        s = {"nowNodeCode": now, "nextNodeCode": nxt, "skipName": name, "skipType": "PASS"}
        if cond:
            s["skipCondition"] = cond
        return s

    return {
        "flowCode": FLOW_CODE,
        "flowName": "L2复验-网关位于审批之后-S115",
        "modelValue": "CLASSICS",
        "nodeList": [
            node("start", "开始", 0, coord="80,240",
                 skips=[skip("start", "apply", "提交")]),
            node("apply", "提交申请", 1, perm="1", coord="230,240",
                 skips=[skip("apply", "gw", "审核")]),
            node("gw", "天数分档", 3, coord="400,240",
                 skips=[skip("gw", "leader", "3天以内", "le@@days|3"),
                        skip("gw", "director", "3天以上", "gt@@days|3")]),
            node("leader", "主管审批", 1, perm="1", coord="560,160",
                 skips=[skip("leader", "end", "同意")]),
            node("director", "负责人审批", 1, perm="1", coord="560,320",
                 skips=[skip("director", "end", "同意")]),
            node("end", "结束", 2, coord="720,240"),
        ],
    }


def pending_task(hdr, instance_id):
    r = requests.get(f"{BASE}/workflow/task/pending/page",
                     params={"pageNum": 1, "pageSize": 50}, headers=hdr, timeout=10)
    tasks = check(r, "待办分页")["data"]["list"]
    hit = [t for t in tasks if str(t.get("instanceId")) == str(instance_id)]
    return hit[0] if hit else None


def main():
    hdr = {}
    ins_ids = []
    def_id = None
    try:
        log("STEP1", "登录 admin ...")
        body = check(requests.post(f"{BASE}/system/auth/login",
                                   json={"username": "admin", "password": "admin123"}, timeout=60), "登录")
        hdr = {"Authorization": body["data"]["token"]}

        log("STEP2", f"save-json 一次性定义 {FLOW_CODE}（网关位于 apply 之后）...")
        body = check(requests.post(f"{BASE}/warm-flow/save-json", json=gw_after_apply_defjson(),
                                   headers={**hdr, "onlyNodeSkip": "false"}, timeout=30), "save-json")
        if body.get("code") not in (0, 200):
            raise AssertionError(f"save-json 失败: {json.dumps(body, ensure_ascii=False)[:300]}")
        r = requests.get(f"{BASE}/workflow/definition/page",
                         params={"pageNum": 1, "pageSize": 50, "flowCode": FLOW_CODE},
                         headers=hdr, timeout=10)
        defs = [d for d in check(r, "定义分页")["data"]["list"] if d.get("isPublish") != 9]
        if not defs:
            raise AssertionError("save-json 后未查到定义")
        def_id = max(defs, key=lambda d: int(d["id"]))["id"]
        r = requests.put(f"{BASE}/workflow/definition/{def_id}/publish", headers=hdr, timeout=10)
        if check(r, "发布定义").get("code") != 0:
            raise AssertionError(f"发布失败: {r.text[:200]}")
        log("PASS2", f"定义已发布 id={def_id}")

        # ── A1：无变量发起（网关不在发起路径上 → 应成功）──────────
        log("STEP3", "A1 无变量发起（新形态下应成功，实例落在 apply）...")
        body = check(requests.post(f"{BASE}/workflow/instance/start",
                                   json={"flowCode": FLOW_CODE, "variable": {}},
                                   headers=hdr, timeout=30), "无变量发起")
        if body.get("code") != 0:
            raise AssertionError(f"新定义形态下无变量发起应成功，实际: "
                                 f"{json.dumps(body, ensure_ascii=False)[:300]}")
        ins_a = body["data"]["id"]
        ins_ids.append(ins_a)
        node = body["data"].get("nodeName")
        if node != "提交申请":
            raise AssertionError(f"实例应落在「提交申请」，实际 {node}")
        log("PASS3", f"发起成功 instanceId={ins_a}，停靠节点={node}")

        # ── A2：推进 apply → 网关条件全不匹配 → L2 友好文案 ──────
        log("STEP4", "A2 通过 apply（网关求值时条件全不匹配，应为翻译后的业务文案）...")
        task = pending_task(hdr, ins_a)
        if task is None:
            raise AssertionError(f"未取到实例 {ins_a} 的待办任务，无法推进到网关")
        body = check(requests.put(f"{BASE}/workflow/task/pass", headers=hdr, timeout=30,
                                  json={"taskId": task["id"], "message": "S115 L2 复验"}), "通过 apply")
        code, msg = body.get("code"), body.get("msg") or ""
        log("STEP4", f"code={code} msg={msg[:120]}")
        if code == 0:
            raise AssertionError("网关条件全不匹配时竟然放行——条件分支语义可能被破坏")
        if "系统内部错误" in msg:
            raise AssertionError(f"L2 未清偿：文案仍为系统内部错误（{msg}）")
        if "条件" not in msg:
            raise AssertionError(f"文案非预期的网关条件提示：{msg}")
        log("PASS4", f"L2 守卫生效（新触发点 pass）：code={code} 文案=「{msg[:60]}」")

        # ── B：带变量发起 → 推进 apply → gt 分支路由 ──────────────
        log("STEP5", "B 带 days=5 发起并通过 apply（应走 gt 分支到负责人审批）...")
        body = check(requests.post(f"{BASE}/workflow/instance/start",
                                   json={"flowCode": FLOW_CODE, "businessName": "S115 L2 回归",
                                         "variable": {"days": 5}}, headers=hdr, timeout=30), "带变量发起")
        if body.get("code") != 0:
            raise AssertionError(f"带变量发起失败: {json.dumps(body, ensure_ascii=False)[:300]}")
        ins_b = body["data"]["id"]
        ins_ids.append(ins_b)
        task = pending_task(hdr, ins_b)
        if task is None:
            raise AssertionError(f"未取到实例 {ins_b} 的待办任务")
        body = check(requests.put(f"{BASE}/workflow/task/pass", headers=hdr, timeout=30,
                                  json={"taskId": task["id"], "message": "S115 L2 回归"}), "通过 apply(带变量)")
        if body.get("code") != 0:
            raise AssertionError(f"带变量推进失败: {json.dumps(body, ensure_ascii=False)[:300]}")
        r = requests.get(f"{BASE}/workflow/instance/{ins_b}", headers=hdr, timeout=10)
        node_b = (check(r, "实例详情").get("data") or {}).get("nodeName")
        if node_b != "负责人审批":
            raise AssertionError(f"days=5 应路由到「负责人审批」，实际 {node_b}")
        log("PASS5", f"路由正确：instanceId={ins_b} 当前节点={node_b}")

        print()
        print("=" * 62)
        print("  L2 复验（网关位于审批之后形态）：全部通过 ✓")
        print(f"  一次性定义 : {FLOW_CODE}（id={def_id}，自清删除）")
        print(f"  A 无变量   : 发起成功 → 推进时被拒（{'' if True else ''}业务文案，非系统内部错误）")
        print("  B 带变量   : days=5 → gt 分支「负责人审批」路由正确")
        print("=" * 62)
        return 0
    finally:
        for ins in ins_ids:
            try:
                r = requests.put(f"{BASE}/workflow/instance/{ins}/terminate", headers=hdr, timeout=10)
                log("CLEAN", f"终止实例 {ins}: code={r.json().get('code')}")
            except Exception as e:  # noqa: BLE001
                log("WARN", f"终止实例 {ins} 失败：{e}")
        # 定义删除守卫（S109 K31）：挂过实例的定义即使实例已终止也删不掉（API 回 1500），
        # 故先物理清理本轮实例及其附属行，再走 API 删定义；API 仍失败则物理删定义兜底，
        # 保证「自清不留 fixture」（避免重演 l2_gw_first_s108 定义逐次堆积 3 份的既有残渣）。
        if ins_ids:
            try:
                log("CLEAN", f"物理清理本轮实例: {purge_flow_instances(ins_ids)}")
            except Exception as e:  # noqa: BLE001
                log("WARN", f"物理清理实例失败（请手工清理 {ins_ids}）：{e}")
        if def_id:
            try:
                r = requests.delete(f"{BASE}/workflow/definition/{def_id}", headers=hdr, timeout=10)
                code = r.json().get("code")
                log("CLEAN", f"API 删除一次性定义 {def_id}: code={code}")
                if code != 0:
                    log("CLEAN", f"API 删除被守卫拒绝，物理清理定义: {purge_flow_definition(def_id)}")
            except Exception as e:  # noqa: BLE001
                log("WARN", f"删除定义 {def_id} 失败：{e}")


if __name__ == "__main__":
    try:
        sys.exit(main())
    except AssertionError as e:
        log("FAIL", str(e))
        sys.exit(1)
