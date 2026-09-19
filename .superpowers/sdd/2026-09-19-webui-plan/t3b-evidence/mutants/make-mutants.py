#!/usr/bin/env python3
# M7b T3 变异体生成器（锚点自证：锚点恰匹配 1 次，替换后字节必变）。
# 生成的每份变异体与原件逐字节不同；原件由调用方另行备份到 mutants/orig/。
import hashlib
import pathlib
import sys

ROOT = pathlib.Path("/home/cna/SimulatorMosire/.claude/worktrees/m7bt3")
EV = ROOT / "t3b-evidence"
MUT = EV / "mutants"

CASES = [
    {
        "name": "m1",
        "src": ROOT / "simos-app/src/main/java/io/mosire/simos/app/gui/ApiViews.java",
        "anchor": (
            '    view.put("reachable", reachable);\n'
            '    view.put("path", hexCoords(reachable ? path : List.of()));\n'
        ),
        "replacement": (
            "    List<HexCoord> emitted = path;\n"
            "    if (path.size() >= 3) {\n"
            "      emitted = List.of(path.get(0), path.get(path.size() - 1));\n"
            "    }\n"
            '    view.put("reachable", reachable);\n'
            '    view.put("path", hexCoords(reachable ? emitted : List.of()));\n'
        ),
    },
    {
        "name": "m2",
        "src": ROOT / "simos-app/src/main/resources/webui/map.js",
        "anchor": (
            '    if (app.getState().mode !== "unit") {\n'
            "      return false;\n"
            "    }\n"
            "    var id = selectedUnitId();\n"
        ),
        "replacement": ("    var id = selectedUnitId();\n"),
    },
]


def md5(path):
    return hashlib.md5(path.read_bytes()).hexdigest()


def main():
    MUT.mkdir(parents=True, exist_ok=True)
    for case in CASES:
        src = case["src"]
        text = src.read_text(encoding="utf-8")
        count = text.count(case["anchor"])
        if count != 1:
            print("FATAL anchor count=%d for %s" % (count, case["name"]), file=sys.stderr)
            return 2
        mutant = text.replace(case["anchor"], case["replacement"])
        out_dir = MUT / case["name"]
        out_dir.mkdir(parents=True, exist_ok=True)
        out = out_dir / src.name
        out.write_text(mutant, encoding="utf-8")
        if out.read_bytes() == src.read_bytes():
            print("FATAL mutant bytes identical for %s" % case["name"], file=sys.stderr)
            return 2
        print(
            "%s anchor=1 orig_md5=%s mutant_md5=%s out=%s"
            % (case["name"], md5(src), md5(out), out)
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
