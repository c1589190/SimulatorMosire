#!/usr/bin/env python3
"""端到端实测：真 provider（121.40.130.178:3000）经 app 的 LLM 路径跑一次「开始决策」。
显式标注：本脚本会**打真外网**（唯一允许的一步）。"""
import json
import os
import sys
import urllib.request

BASE = os.environ.get("SIMOS_BASE", "http://127.0.0.1:5821")
os.environ["no_proxy"] = "*"
os.environ["NO_PROXY"] = "*"
for k in ("http_proxy", "https_proxy", "HTTP_PROXY", "HTTPS_PROXY", "all_proxy", "ALL_PROXY"):
    os.environ.pop(k, None)


def http(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        BASE + path, data=data, method=method,
        headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.status, json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode() or "{}")


def state():
    _, s = http("GET", "/api/state")
    return s


def submit(ctype, payload):
    rev = state()["heads"]["main"]
    status, resp = http("POST", "/api/command", {
        "branch": "main", "expectedRevision": rev, "type": ctype,
        "payloadJson": json.dumps(payload, ensure_ascii=False)})
    print(f"[cmd] {ctype} rev={rev} -> {status} {json.dumps(resp, ensure_ascii=False)[:200]}")
    return status, resp


def main():
    print("== state before ==", json.dumps(state(), ensure_ascii=False))
    submit("map.CreateRegion", {
        "regionId": "r-dashu", "name": "大蜀", "hexes": [{"q": 0, "r": 0}, {"q": 1, "r": 1}, {"q": -1, "r": -1}],
        "meta": {"color": "#c0392b", "tag": "nation:dashu", "description": "E2E 夹具区域"}})
    submit("sd.CreateNation", {
        "nationId": "dashu", "name": "大蜀", "homeRegionId": "r-dashu", "adminBudgetPerTick": 100})
    submit("sd.CreateDecisionMaker", {
        "id": "dm-dashu", "affiliation": {"kind": "nation", "id": "dashu"}, "allowedTools": [], "cadence": 1})
    submit("sd.CreateCombat", {"combatId": "c1", "name": "首战", "participants": []})
    submit("sd.AddCombatStage", {
        "combatId": "c1", "combatStateId": "cs1", "hex": {"q": 0, "r": 0},
        "stage": {
            "stageId": "s1", "name": "交锋", "participants": [],
            "outcomes": {"options": [
                {"id": "o-win", "label": "我军获胜", "weight": 1, "casualties": {"personnel": 100}},
                {"id": "o-lose", "label": "我军失利", "weight": 1, "casualties": {"personnel": 300}},
            ]},
        }})
    rev = state()["heads"]["main"]
    status, resp = http("POST", "/api/sd/set-decision-maker-provider", {
        "branch": "main", "expectedRevision": rev, "decisionMakerId": "dm-dashu",
        "providerId": "mosire-flash"})
    print(f"[bind] dm-dashu -> mosire-flash rev={rev} -> {status} {json.dumps(resp, ensure_ascii=False)[:200]}")
    print("== state before start ==", json.dumps(state(), ensure_ascii=False))

    rev = state()["heads"]["main"]
    status, resp = http("POST", "/api/sd/start-decision", {
        "branch": "main", "expectedRevision": rev, "decisionMakerId": "dm-dashu",
        "note": "E2E 真 LLM 判决"})
    print(f"\n== /api/sd/start-decision rev={rev} -> HTTP {status} ==")
    print(json.dumps(resp, ensure_ascii=False, indent=2)[:4000])
    print("\n== state after ==", json.dumps(state(), ensure_ascii=False))
    _, verdicts = http("GET", "/api/sd/verdicts")
    print("== verdicts ==", json.dumps(verdicts, ensure_ascii=False)[:2000])


if __name__ == "__main__":
    sys.exit(main())
