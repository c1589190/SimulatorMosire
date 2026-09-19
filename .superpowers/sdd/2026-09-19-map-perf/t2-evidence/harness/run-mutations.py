#!/usr/bin/env python3
# M9 T2/T4/T5 —— 变异轮装置。
# 纪律：每轮先恢复干净世界（pristine + md5 自证），再推入变异体（md5 必须与 pristine 不同），
#       跑 e2e，记录本轮实际红点，还原后再自证 md5 回到 pristine。装置把"本轮跑的是哪份字节"写进日志。
import hashlib, json, os, shutil, subprocess, sys

WT = "/home/cna/SimulatorMosire/.claude/worktrees/m9t2"
E = WT + "/.superpowers/sdd/2026-09-19-map-perf/t2-evidence"
WEB = WT + "/simos-app/src/main/resources/webui"
BASE = "http://127.0.0.1:45911"
NODE_ENV = dict(os.environ)
NODE_ENV["NODE_PATH"] = "/home/cna/.npm/_npx/e41f203b7505f1fb/node_modules"

FILES = {
    "map.js": WEB + "/map.js",
    "api.js": WEB + "/api.js",
    "panels.js": WEB + "/panels.js",
    "app.js": WEB + "/app.js",
    "timeline.js": WEB + "/timeline.js",
    "unitTree.js": WEB + "/unitTree.js",
}
PRISTINE = "/tmp/m9t2/pristine"

MUTATIONS = [
    dict(
        id="A-m1-remove-memo",
        file="api.js",
        old="    var hit = dataCache[url];\n    if (hit) {\n      return hit;\n    }",
        new="    var hit = null;\n    if (hit) {\n      return hit;\n    }",
        expect_red=["a-memo-no-new-request"],
    ),
    dict(
        id="A-m2-target-not-in-key",
        file="api.js",
        old="    var url = withTarget(path, target);\n    var hit = dataCache[url];",
        new="    var url = path;\n    var hit = dataCache[url];",
        expect_red=["a-target-invalidates"],
    ),
    dict(
        id="B-m1-pan-rebuilds",
        file="map.js",
        old="      if (\n        Math.abs(view.tx - terrainCache.tx) > TERRAIN_CACHE_MARGIN ||\n        Math.abs(view.ty - terrainCache.ty) > TERRAIN_CACHE_MARGIN\n      ) {\n        rebuildTerrainCanvas();\n      }",
        new="      rebuildTerrainCanvas();",
        expect_red=["b-pan-does-not-rebuild"],
    ),
    dict(
        id="B-m2-resize-no-rebuild",
        file="map.js",
        old="      if (perfConfig.terrainCacheEnabled) {\n        ensureTerrainCanvas();\n        terrainDirty = true;\n      }",
        new="      if (perfConfig.terrainCacheEnabled) {\n        ensureTerrainCanvas();\n      }",
        expect_red=["b-resize-rebuilds-terrain"],
    ),
    dict(
        id="B-m3-no-border-lod",
        file="map.js",
        old='    borderMinScreenPx: 4,',
        new='    borderMinScreenPx: 0,',
        expect_red=["b-border-lod-skipped-at-fit"],
    ),
    dict(
        id="B-m4-giant-reverted",
        file="map.js",
        old='    terrainMode: "chunked",\n    terrainChunk: 32,',
        new='    terrainMode: "giant",\n    terrainChunk: 32,',
        expect_red=["b-render-cold-p50-lt33"],
    ),
    dict(
        id="C-m1-legend-no-memo",
        file="map.js",
        old="      if (legendOverviewRef === overview && legendCounts) {",
        new="      if (false && legendOverviewRef === overview && legendCounts) {",
        expect_red=["c-legend-memoized-scan-once-per-overview"],
    ),
]


def md5(path):
    return hashlib.md5(open(path, "rb").read()).hexdigest()


def run_e2e(tag):
    out = E + "/micro/mut-" + tag + ".json"
    log = E + "/logs/mut-" + tag + ".log"
    with open(log, "w") as lf:
        rc = subprocess.call(
            ["node", E + "/harness/t2-e2e.cjs", BASE, out],
            stdout=lf, stderr=subprocess.STDOUT, env=NODE_ENV, timeout=400,
        )
    data = json.load(open(out)) if os.path.exists(out) else {}
    return rc, data


def main():
    os.makedirs(PRISTINE, exist_ok=True)
    for name, path in FILES.items():
        shutil.copy2(path, PRISTINE + "/" + name)
    print("pristine md5:")
    for name in FILES:
        print("  %-10s %s" % (name, md5(FILES[name])))

    only = sys.argv[1:] if len(sys.argv) > 1 else None
    rounds = []
    for m in MUTATIONS:
        if only and m["id"] not in only:
            continue
        path = FILES[m["file"]]
        # 1) 恢复干净世界 + 自证
        shutil.copy2(PRISTINE + "/" + m["file"], path)
        clean_md5 = md5(path)
        src = open(path, "r", encoding="utf-8").read()
        cnt = src.count(m["old"])
        if cnt != 1:
            print("!! %s: old 片段命中 %d 次（应为 1），本轮作废" % (m["id"], cnt))
            rounds.append({"id": m["id"], "aborted": "needle count %d" % cnt})
            continue
        # 2) 推入变异体 + 自证字节已变
        mutated = src.replace(m["old"], m["new"])
        open(path, "w", encoding="utf-8").write(mutated)
        mutant_md5 = md5(path)
        if mutant_md5 == clean_md5:
            print("!! %s: 变异后 md5 未变，本轮作废" % m["id"])
            rounds.append({"id": m["id"], "aborted": "md5 unchanged"})
            continue
        print("\n== round %s: clean_md5=%s mutant_md5=%s ==" % (m["id"], clean_md5, mutant_md5))
        rc, data = run_e2e(m["id"])
        fails = [a["id"] for a in data.get("assertions", []) if not a["pass"]]
        passed = [a["id"] for a in data.get("assertions", []) if a["pass"]]
        killed = all(x in fails for x in m["expect_red"])
        print("   expect_red=%s" % m["expect_red"])
        print("   actual_fails=%s" % fails)
        print("   killed=%s rc=%s" % (killed, rc))
        rounds.append({
            "id": m["id"], "clean_md5": clean_md5, "mutant_md5": mutant_md5,
            "expected_red": m["expect_red"], "actual_fails": fails,
            "pass_count": len(passed), "fail_count": len(fails), "killed": killed, "rc": rc,
        })
        # 3) 还原 + 自证回到干净世界
        shutil.copy2(PRISTINE + "/" + m["file"], path)
        if md5(path) != clean_md5:
            print("!! %s: 还原后 md5 与 clean 不符！" % m["id"])
            rounds[-1]["restore_ok"] = False
        else:
            rounds[-1]["restore_ok"] = True

    print("\n=== MUTATION SUMMARY ===")
    for r in rounds:
        print(json.dumps(r, ensure_ascii=False))
    json.dump(rounds, open(E + "/micro/mutations.json", "w"), ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
