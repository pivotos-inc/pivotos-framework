#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
S103 C4 一票否决验证：BPMN XML → warm-flow DefJson 映射最小样例服务端实证。

链路：BPMN 样例 XML（开始→提交申请→互斥网关(le/gt@@days|3)→主管/负责人审批→结束）
  → 映射为 DefJson（映射口径与 ui apps/admin/src/utils/workflow/bpmnDefJson.ts 一致）
  → POST /warm-flow/save-json（@RequestBody DefJson + @RequestHeader onlyNodeSkip=false）
  → PUT /workflow/definition/{id}/publish 发布
  → query-def 回读比对（skipCondition/permissionFlag 不丢）
  → 发起 days=1 → 断言待办落在「主管审批」→ 通过 → 实例完结
  → 发起 days=5 → 断言待办落在「部门负责人审批」→ 通过 → 实例完结

通过 = 映射语义被引擎完整接受，一票否决点 PASS；任何一步失败 = 改道（14 号清单第六节）。
前置：8080 dev 运行中（库 pivotos_dev），admin/admin123。
"""
import json
import sys
import time
import xml.etree.ElementTree as ET

import requests

BASE = "http://localhost:8080"
FLOW_CODE = "bpmn_s103_veto"
RUN_TS = str(int(time.time()))[-6:]  # 每次运行独立 businessName，避免与残留实例撞车
WARM_NS = "http://pivotos/warm-flow"
BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL"

SAMPLE_XML = """<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
  xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
  xmlns:warm="http://pivotos/warm-flow">
  <bpmn:process id="bpmn_s103_veto" name="请假分档审批-S103映射验证" isExecutable="false">
    <bpmn:startEvent id="start" name="开始"/>
    <bpmn:userTask id="apply" name="提交申请" warm:permissionFlag="1"/>
    <bpmn:exclusiveGateway id="gw" name="天数分档"/>
    <bpmn:userTask id="leader" name="主管审批" warm:permissionFlag="1"/>
    <bpmn:userTask id="director" name="部门负责人审批" warm:permissionFlag="1"/>
    <bpmn:endEvent id="end" name="结束"/>
    <bpmn:sequenceFlow id="f1" sourceRef="start" targetRef="apply" name="提交"/>
    <bpmn:sequenceFlow id="f2" sourceRef="apply" targetRef="gw" name="提交"/>
    <bpmn:sequenceFlow id="f3" sourceRef="gw" targetRef="leader" name="3天以内">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">le@@days|3</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="f4" sourceRef="gw" targetRef="director" name="3天以上">
      <bpmn:conditionExpression xsi:type="bpmn:tFormalExpression">gt@@days|3</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="f5" sourceRef="leader" targetRef="end" name="同意"/>
    <bpmn:sequenceFlow id="f6" sourceRef="director" targetRef="end" name="同意"/>
  </bpmn:process>
</bpmn:definitions>"""

NODE_TYPE = {"startEvent": 0, "userTask": 1, "endEvent": 2,
             "exclusiveGateway": 3, "parallelGateway": 4, "inclusiveGateway": 5}
# 坐标（与 ui 样例 XML 的 DI 一致，引擎只存不算）
COORDS = {"start": "80,240", "apply": "180,218", "gw": "340,233",
          "leader": "450,158", "director": "450,278", "end": "640,240"}


def log(tag, msg):
    print(f"[{tag}] {msg}", flush=True)


def bpmn_xml_to_defjson(xml_text):
    """BPMN XML → DefJson（映射口径镜像 ui bpmnDefJson.ts，纯标准库实现）"""
    root = ET.fromstring(xml_text)
    process = root.find(f"{{{BPMN_NS}}}process")
    assert process is not None, "缺少 process 元素"
    nodes, edges = {}, []
    for child in process:
        tag = child.tag.split("}")[-1]
        if tag in NODE_TYPE:
            nodes[child.get("id")] = {
                "nodeType": NODE_TYPE[tag],
                "nodeCode": child.get("id"),
                "nodeName": child.get("name") or child.get("id"),
                "permissionFlag": child.get(f"{{{WARM_NS}}}permissionFlag"),
                "nodeRatio": "0.000",
                "coordinate": COORDS.get(child.get("id"), "0,0"),
            }
        elif tag == "sequenceFlow":
            cond = child.find(f"{{{BPMN_NS}}}conditionExpression")
            edges.append({
                "nowNodeCode": child.get("sourceRef"),
                "nextNodeCode": child.get("targetRef"),
                "skipName": child.get("name") or "",
                "skipType": "PASS",
                **({"skipCondition": cond.text.strip()} if cond is not None and cond.text else {}),
            })
    node_list = []
    for code, n in nodes.items():
        skips = [dict(e) for e in edges if e["nowNodeCode"] == code]
        node_list.append({k: v for k, v in n.items() if v is not None} | {"skipList": skips})
    return {
        "flowCode": process.get("id"),
        "flowName": process.get("name"),
        "modelValue": "CLASSICS",
        "nodeList": node_list,
    }


def main():
    # Step 1 登录
    log("STEP1", "登录 admin ...")
    r = requests.post(f"{BASE}/system/auth/login",
                      json={"username": "admin", "password": "admin123"}, timeout=30)
    body = r.json()
    assert r.status_code == 200 and body.get("code") == 0, f"登录失败: {body}"
    hdr = {"Authorization": body["data"]["token"]}

    # Step 2 映射 + save-json
    log("STEP2", "BPMN XML → DefJson 映射并 save-json ...")
    def_json = bpmn_xml_to_defjson(SAMPLE_XML)
    log("STEP2", "映射产物：" + json.dumps(def_json, ensure_ascii=False)[:400])
    r = requests.post(f"{BASE}/warm-flow/save-json", json=def_json,
                      headers={**hdr, "onlyNodeSkip": "false"}, timeout=30)
    body = r.json()
    assert body.get("code") in (0, 200), f"save-json 失败: {json.dumps(body, ensure_ascii=False)[:300]}"
    log("PASS2", "save-json 接受映射产物")

    # Step 3 定位定义 ID 并发布
    log("STEP3", "定位定义并发布 ...")
    r = requests.get(f"{BASE}/workflow/definition/page",
                     params={"pageNum": 1, "pageSize": 50, "flowCode": FLOW_CODE},
                     headers=hdr, timeout=10)
    defs = [d for d in r.json()["data"]["list"] if d.get("isPublish") != 9]
    assert defs, "save-json 后未查到定义"
    defs.sort(key=lambda d: int(d["id"]), reverse=True)
    def_id = defs[0]["id"]
    r = requests.put(f"{BASE}/workflow/definition/{def_id}/publish", headers=hdr, timeout=10)
    body = r.json()
    assert body.get("code") == 0, f"发布失败: {json.dumps(body, ensure_ascii=False)[:300]}"
    log("PASS3", f"发布成功 id={def_id} version={defs[0].get('version')}")

    # Step 4 query-def 回读比对（条件/权限不丢 = 映射语义被完整持久化）
    log("STEP4", "query-def 回读比对 ...")
    r = requests.get(f"{BASE}/warm-flow/query-def/{def_id}", headers=hdr, timeout=10)
    rb = r.json()
    assert rb.get("code") in (0, 200), f"query-def 失败: {rb}"
    node_map = {n["nodeCode"]: n for n in rb["data"]["nodeList"]}
    assert node_map["gw"]["nodeType"] == 3, f"互斥网关应映射 nodeType=3(SERIAL)，实际 {node_map['gw']['nodeType']}"
    gw_conds = {s["nextNodeCode"]: s.get("skipCondition") for s in node_map["gw"]["skipList"]}
    assert gw_conds.get("leader") == "le@@days|3", f"le 分支条件丢失/错位: {gw_conds}"
    assert gw_conds.get("director") == "gt@@days|3", f"gt 分支条件丢失/错位: {gw_conds}"
    assert node_map["leader"].get("permissionFlag") == "1", "leader permissionFlag 丢失"
    assert node_map["apply"].get("permissionFlag") == "1", "apply permissionFlag 丢失"
    log("PASS4", f"回读比对通过：网关 nodeType=3，条件 {gw_conds}，permissionFlag 完整")

    # Step 5/6 双分支发起实证
    for days, expect_node in ((1, "主管审批"), (5, "部门负责人审批")):
        tag = f"STEP{4 + (0 if days == 1 else 1)}"
        biz = f"S103映射验证-days{days}-{RUN_TS}"
        log(tag, f"days={days} 发起（{biz}），期望落到「{expect_node}」...")
        r = requests.post(f"{BASE}/workflow/instance/start",
                          json={"flowCode": FLOW_CODE, "businessName": biz,
                                "variable": {"days": days}}, headers=hdr, timeout=30)
        body = r.json()
        assert body.get("code") == 0, f"发起失败: {json.dumps(body, ensure_ascii=False)[:300]}"

        # 待办定位（apply 提交申请节点也是 admin 自己——先过 apply 节点）
        # 口径：发起入参 businessName 在待办 VO 中落在 businessId 字段
        for hop in range(3):
            r = requests.get(f"{BASE}/workflow/task/pending/page",
                             params={"pageNum": 1, "pageSize": 50},
                             headers=hdr, timeout=10)
            tasks = [t for t in r.json()["data"]["list"] if t.get("businessId") == biz]
            if not tasks:
                break
            cur = tasks[0]
            log(tag, f"  待办 hop{hop}：nodeName={cur.get('nodeName')}")
            if cur.get("nodeName") == expect_node:
                break
            r = requests.put(f"{BASE}/workflow/task/pass",
                             json={"taskId": cur["id"], "message": "S103 E2E 自动通过"},
                             headers=hdr, timeout=15)
            pb = r.json()
            assert pb.get("code") == 0, f"过节点失败: {json.dumps(pb, ensure_ascii=False)[:300]}"
        else:
            raise AssertionError("跳转次数超限")
        assert cur.get("nodeName") == expect_node, \
            f"days={days} 未落到「{expect_node}」，实际停在「{cur.get('nodeName')}」——网关条件映射失效"
        # 审批通过直至完结
        r = requests.put(f"{BASE}/workflow/task/pass",
                         json={"taskId": cur["id"], "message": "S103 E2E 通过"},
                         headers=hdr, timeout=15)
        assert r.json().get("code") == 0, "最终审批通过失败"
        log(f"PASS{5 if days == 1 else 6}", f"days={days} → 「{expect_node}」走向正确，审批通过完结")

    print("\n===== S103 一票否决验证 ALL-PASS：BPMN↔DefJson 映射语义被引擎完整接受 =====")


if __name__ == "__main__":
    try:
        main()
    except AssertionError as e:
        print(f"\n[FAIL] {e}", flush=True)
        sys.exit(1)
