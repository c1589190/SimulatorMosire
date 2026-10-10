#!/usr/bin/env python3
"""W1 验收：把 checkpoint 信封投影成**跨版本可比**的规范状态（CCS, canonical comparable state）。

口径（唯一一处形状投影，其余原样保留）：
  · 改前 `economy.data.markets`(Map<hex "q_r", {numeraire,prices}>)
    改后 `economy.data.marketTables`(Map<marketId, {numeraire,hexes,prices}>)
    ⇒ 两者都投影成 `marketsByHex` = {hex: {"numeraire","prices"}}（改后的 hexes 是结构字段，不进投影）。
  · `economy.data.marketRoundStats`（W1 新增组件）从投影中剔除（白名单②）。
  · 其余模块载荷 parse 后原样保留（deep compare 用）。
输出：排序键的规范 JSON。
"""
import json
import sys


def load_modules(cp_path):
    with open(cp_path) as f:
        env = json.load(f)
    out = {}
    for ns, txt in env["modules"].items():
        out[ns] = json.loads(txt)
    return {
        "ref": env["ref"],
        "timestamp": env["timestamp"],
        "timeBase": env["timeBase"],
        "modules": out,
    }


def project(cp_path):
    state = load_modules(cp_path)
    economy = state["modules"]["economy"]
    data = economy["data"]

    markets_by_hex = {}
    shape = {}
    if "markets" in data:  # 改前：逐格价表
        shape["source"] = "markets(per-hex)"
        for hex_key, market in data["markets"].items():
            markets_by_hex[hex_key] = {
                "numeraire": market["numeraire"],
                "prices": market["prices"],
            }
    elif "marketTables" in data:  # 改后：每区一张
        shape["source"] = "marketTables(per-zone)"
        for market_id, table in data["marketTables"].items():
            for hx in table["hexes"]:
                hex_key = "%d_%d" % (hx["q"], hx["r"])
                markets_by_hex[hex_key] = {
                    "numeraire": table["numeraire"],
                    "prices": table["prices"],
                }
        shape["tableIds"] = sorted(data["marketTables"].keys())
    else:
        shape["source"] = "none"

    new_components = []
    for key in list(data.keys()):
        if key in ("markets", "marketTables"):
            continue
        if key == "marketRoundStats":
            new_components.append(key)
            continue
        # 其余组件原样保留
    projected_economy = {k: v for k, v in data.items() if k not in ("markets", "marketTables")}
    projected_economy.pop("marketRoundStats", None)
    projected_economy["marketsByHex"] = markets_by_hex

    modules = dict(state["modules"])
    modules["economy"] = {"ref": economy.get("ref"), "timestamp": economy.get("timestamp"),
                          "data": projected_economy}

    return {
        "ref": state["ref"],
        "timestamp": state["timestamp"],
        "timeBase": state["timeBase"],
        "shape": shape,
        "removedNewComponents": new_components,
        "modules": modules,
    }


if __name__ == "__main__":
    src, dst = sys.argv[1], sys.argv[2]
    proj = project(src)
    with open(dst, "w") as f:
        json.dump(proj, f, ensure_ascii=False, sort_keys=True, indent=1)
    print("投影落盘 %s" % dst)
