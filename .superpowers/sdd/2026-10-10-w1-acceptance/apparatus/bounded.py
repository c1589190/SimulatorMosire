#!/usr/bin/env python3
"""W1 验收：状态有界性 —— 逐组件字节数 + marketRoundStats 叶数与窗口槽位。

用法：python3 bounded.py <checkpoint.json> [<checkpoint.json> ...]
"""
import json
import sys


def leaves(o):
    if isinstance(o, dict):
        return sum(leaves(v) for v in o.values()) + len(o)
    if isinstance(o, list):
        return sum(leaves(v) for v in o) + len(o)
    return 1


def stats_shape(stats):
    out = {}
    for market_id, s in stats.items():
        out[market_id] = {
            "lastRoundDay": s.get("lastRoundDay"),
            "lastRoundCommodities": len(s.get("lastRound", {})),
            "cycleToDateCommodities": len(s.get("cycleToDate", {})),
            "previousCycleCommodities": len(s.get("previousCycle", {})),
            "keys": sorted(s.keys()),
            "bytes": len(json.dumps(s, sort_keys=True)),
            "leaves": leaves(s),
        }
    return out


def main():
    for path in sys.argv[1:]:
        env = json.load(open(path))
        data = json.loads(env["modules"]["economy"])["data"]
        tick = env["timestamp"]["tick"]
        sizes = {k: len(json.dumps(v, sort_keys=True)) for k, v in data.items()}
        total = sum(sizes.values())
        print("=== %s  tick=%s  economy 载荷 %d 字节 / %d 组件 ===" %
              (path.split("/")[-1], tick, total, len(data)))
        top = sorted(sizes.items(), key=lambda kv: -kv[1])[:8]
        print("  最大 8 个组件：" + ", ".join("%s=%dB" % kv for kv in top))
        st = data.get("marketRoundStats")
        if st is None:
            print("  marketRoundStats: 不存在（改前形态）")
        else:
            print("  marketRoundStats: %d 字节 / %d 叶 / %d 个市场；占比 %.4f%%"
                  % (sizes["marketRoundStats"], leaves(st), len(st),
                     100.0 * sizes["marketRoundStats"] / total))
            for mid, sh in stats_shape(st).items():
                print("    市场 %-16s lastRoundDay=%s 商品 %d/%d/%d  键=%s  %dB/%d 叶"
                      % (mid, sh["lastRoundDay"], sh["lastRoundCommodities"],
                         sh["cycleToDateCommodities"], sh["previousCycleCommodities"],
                         ",".join(sh["keys"]), sh["bytes"], sh["leaves"]))
        # 其它可能随天数增长的组件（参考）
        for k in ("flows", "debtContracts", "shipments", "populations", "populationEvents"):
            if k in sizes:
                print("  %-18s %8d 字节" % (k, sizes[k]))


if __name__ == "__main__":
    main()
