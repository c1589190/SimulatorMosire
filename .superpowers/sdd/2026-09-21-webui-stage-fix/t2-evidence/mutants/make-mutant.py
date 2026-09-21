#!/usr/bin/env python3
"""T2 变异体生成器：按 id 对 pristine 文件做一处定点改动，写出同规范名目标文件。

用法：make-mutant.py <m1|m2|m3|m4|m5> <pristine 源> <目标路径>
每处替换都断言"恰好命中一次"，否则非零退出（防静默不生效 ⇒ 假存活）。
"""
import sys
import pathlib

ROUND, SRC, DST = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
text = SRC.read_text(encoding="utf-8")

REPLACEMENTS = {
    # m1：面板可见性去掉互斥（地形恒可见）⇒ 可见性断言应红（C1）。
    "m1": [
        (
            "      terrain: subtool === \"terrain\",\n",
            "      terrain: true,\n",
        )
    ],
    # m2：地形线也放行 map.SetEdge（跨线）⇒ C2 的写命令白名单断言应红。
    "m2": [
        (
            '    terrain: ["map.SetTerrain", "map.RandomizeRegion"],\n',
            '    terrain: ["map.SetTerrain", "map.RandomizeRegion", "map.SetEdge"],\n',
        )
    ],
    # m3：commitEdge 去掉写门（子选项门控失效）⇒ 静态门控断言应红。
    "m3": [
        (
            '    if (!mapEditWriteGate(host.mapEditTool, "map.SetEdge").ok) {\n'
            '      setEdgeStatus("当前不是「连通性」编辑线 ⇒ **未发出任何写命令**。", "warn");\n'
            "      return null;\n"
            "    }\n",
            "",
        )
    ],
    # m4：mapEditSubtoolOf 未知工具兜成「地形」⇒ fail-closed 断言应红。
    "m4": [
        (
            "    return MAP_EDIT_TOOL_SUBTOOL[tool] || null;\n",
            '    return MAP_EDIT_TOOL_SUBTOOL[tool] || "terrain";\n',
        )
    ],
    # m5：REQUIRED_FILES 漏掉新测试文件 ⇒ 在册断言应红。
    "m5": [
        (
            '  "map-edit-suboptions.test.cjs",\n',
            "",
        )
    ],
}

changed = 0
for old, new in REPLACEMENTS[ROUND]:
    hits = text.count(old)
    if hits != 1:
        sys.exit("replacement matched %d times (want 1): %r" % (hits, old[:60]))
    text = text.replace(old, new)
    changed += 1

DST.write_text(text, encoding="utf-8")
print("mutant=%s replacements=%d" % (ROUND, changed))
