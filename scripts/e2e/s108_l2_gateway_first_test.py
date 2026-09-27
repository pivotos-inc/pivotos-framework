#!/usr/bin/env python3
"""
S108 回归：L2 清偿复验（flow_start_exception_test.py 的 fixture 漂移修正版）。
背景：原脚本假设「网关紧跟开始节点」，无变量发起时条件全不匹配 → 引擎 FlowException →
服务层翻译为业务文案。S103~S105 将 fixture leave_tier_s103 迭代到 v10（开始→提交申请→网关），
网关进入运行时评估，原假设失效（fixture 漂移，非引擎回归）。
本脚本用一次性定义 l2_gw_first_s108（开始→互斥网关→主管/负责人→结束）还原 S93 原形态复验 L2，
验证后删除定义、终止实例，不留 fixture 残留。
判定口径同原脚本：无变量发起返回业务文案（含「发起失败」或「条件」，非「系统内部错误」）；
匹配变量发起成功（正常链路未破坏）。
"""
import json
import sys

import requests

BASE = "http://localhost:8080"
FLOW_CODE = "l2_gw_first_s108"


def log(tag, msg):
    print(f"[{tag}]", msg, flush=True)


def gw_first_defjson():
    """开始 → 互斥网关(le/gt@@days|3) → 主管/负责人 → 结束（S93 原形态还原）"""
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
        "flowName": "L2复验-网关首节点-S108",
        "modelValue": "CLASSICS",
        "nodeList": [
            node("start", "开始", 0, coord="80,240",
                 skips=[skip("start", "gw", "提交")]),
            node("gw", "天数分档", 3, coord="300,240",
                 skips=[skip("gw", "leader", "3天以内", "le@@days|3"),
                        skip("gw", "director", "3天以上", "gt@@days|3")]),
            node("leader", "主管审批", 1, perm="1", coord="470,160",
                 skips=[skip("leader", "end", "同意")]),
            node("director", "负责人审批", 1, perm="1", coord="470,320",
                 skips=[skip("director", "end", "同意")]),
            node("end", "结束", 2, coord="640,240"),
        ],
    }


def main():
    log("STEP1", "登录 admin ...")
    r = requests.post(f"{BASE}/system/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=30)
    body = r.json()
    assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
    hdr = {"Authorization": body["data"]["token"]}

    log("STEP2", "save-json 建一次性定义（网关紧跟开始）...")
    r = requests.post(f"{BASE}/warm-flow/save-json", json=gw_first_defjson(),
                      headers={**hdr, "onlyNodeSkip": "false"}, timeout=30)
    body = r.json()
    assert body.get("code") in (0, 200), f"save-json 失败: {json.dumps(body, ensure_ascii=False)[:300]}"

    r = requests.get(f"{BASE}/workflow/definition/page",
                     params={"pageNum": 1, "pageSize": 50, "flowCode": FLOW_CODE},
                     headers=hdr, timeout=10)
    defs = [d for d in r.json()["data"]["list"] if d.get("isPublish") != 9]
    assert defs, "save-json 后未查到定义"
    def_id = max(defs, key=lambda d: int(d["id"]))["id"]
    r = requests.put(f"{BASE}/workflow/definition/{def_id}/publish", headers=hdr, timeout=10)
    assert r.json().get("code") == 0, f"发布失败: {r.text[:200]}"
    log("PASS2", f"定义已发布 id={def_id}")

    ins_id = None
    try:
        log("STEP3", "无变量发起（网关条件应全不匹配 → 业务异常文案）...")
        r = requests.post(f"{BASE}/workflow/instance/start",
                          json={"flowCode": FLOW_CODE, "variable": {}}, headers=hdr, timeout=30)
        body = r.json()
        code, msg = body.get("code"), body.get("msg") or ""
        log("STEP3", f"HTTP {r.status_code} code={code} msg={msg[:120]}")
        assert code != 0, "期望发起失败但成功了——条件分支语义可能被破坏"
        assert "系统内部错误" not in msg, f"L2 未清偿：文案仍为系统内部错误（{msg}）"
        assert ("发起失败" in msg) or ("条件" in msg), f"文案非预期业务提示：{msg}"
        log("PASS3", f"L2 清偿生效：业务异常 code={code}，文案=「{msg[:60]}」")

        log("STEP4", "days=5 发起（应匹配 gt 分支 → 负责人审批）...")
        r = requests.post(f"{BASE}/workflow/instance/start",
                          json={"flowCode": FLOW_CODE, "businessName": "L2复验",
                                "variable": {"days": 5}}, headers=hdr, timeout=30)
        body = r.json()
        assert body.get("code") == 0, f"回归失败：匹配变量发起被阻断 {json.dumps(body, ensure_ascii=False)[:300]}"
        ins_id = body["data"]["id"]
        node = body["data"].get("nodeName")
        assert node == "负责人审批", f"days=5 应路由到负责人审批，实际 {node}"
        log("PASS4", f"发起成功 instanceId={ins_id}，路由={node}（gt 分支命中）")
    finally:
        if ins_id:
            r = requests.put(f"{BASE}/workflow/instance/{ins_id}/terminate", headers=hdr, timeout=10)
            log("CLEAN", f"终止实例 {ins_id}：code={r.json().get('code')}")
        r = requests.delete(f"{BASE}/workflow/definition/{def_id}", headers=hdr, timeout=10)
        log("CLEAN", f"删除一次性定义 {def_id}：code={r.json().get('code')}")

    log("ALL-PASS", "L2 复验完成：网关首节点形态下失败文案已翻译 + gt 条件路由正确 + 现场已清理")


if __name__ == "__main__":
    sys.exit(main())
