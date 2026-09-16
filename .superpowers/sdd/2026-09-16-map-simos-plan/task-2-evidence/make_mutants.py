#!/usr/bin/env python3
# 生成 Task 2 的变异体：每个 = 原件副本 + 定向替换（needle 命中次数必须 == 1，否则拒绝生成）。
import pathlib
import sys

REF = pathlib.Path("/tmp/terrainmut/ref")
OUT = pathlib.Path("/tmp/terrainmut/mutants")

# (变异文件名, 目标类名, [(needle, repl), ...])
MUTANTS = [
    (
        "m1.TerrainCatalog.java",
        "TerrainCatalog.java",
        [
            (
                '      throw new IllegalArgumentException("未知地形类型: " + key);',
                '      return defaults().get("plains");',
            ),
        ],
    ),
    (
        # 语义只有一处（保序 Map 换成 Map.copyOf）；删 import 是同一次改动的机械后果——
        # 不删会被 checkstyle 的 UnusedImports 挡在 validate 阶段，那时根本没跑到测试。
        "m2.TerrainCatalog.java",
        "TerrainCatalog.java",
        [
            ("    return Collections.unmodifiableMap(m);", "    return Map.copyOf(m);"),
            ("import java.util.Collections;\n", ""),
        ],
    ),
    (
        "m3.TerrainCatalog.java",
        "TerrainCatalog.java",
        [('new TerrainType("plains", "平原",', 'new TerrainType("plains", "山区",')],
    ),
    (
        # 删 KEYS 里的一项（6 项）
        "m4a.TerrainCatalog.java",
        "TerrainCatalog.java",
        [
            (
                '"ocean", "plains", "desert", "low_hills", "mountains", "plateau", "plateau_mountains");',
                '"ocean", "plains", "desert", "low_hills", "mountains", "plateau");',
            )
        ],
    ),
    (
        # 删 defaults() 里的一整行（低矮丘陵）——**KEYS 不动**
        "m4b.TerrainCatalog.java",
        "TerrainCatalog.java",
        [
            (
                '    m.put(\n'
                '        "low_hills",\n'
                '        new TerrainType(\n'
                '            "low_hills", "低矮丘陵", "#A8B36A", 0.55, 0.65, 2, 1, 1, 2, "低地与山地的过渡带，产量中等、略难走"));\n',
                "",
            )
        ],
    ),
    (
        # 造一条缝：plains 的带尾 0.45 -> 0.44
        "m5.TerrainCatalog.java",
        "TerrainCatalog.java",
        [("0.30, 0.45, 3, 0, 0, 1,", "0.30, 0.44, 3, 0, 0, 1,")],
    ),
    (
        # 交换 KEYS 里 plateau 与 plateau_mountains
        "m6.TerrainCatalog.java",
        "TerrainCatalog.java",
        [
            (
                '"ocean", "plains", "desert", "low_hills", "mountains", "plateau", "plateau_mountains");',
                '"ocean", "plains", "desert", "low_hills", "mountains", "plateau_mountains", "plateau");',
            )
        ],
    ),
    (
        # plains 的 moveCost 改成全表最大（1000 > 海洋的 999，故"严格最小"必然不成立）
        "m7.TerrainCatalog.java",
        "TerrainCatalog.java",
        [("0.45, 3, 0, 0, 1,", "0.45, 3, 0, 0, 1000,")],
    ),
    (
        # 把 plains 的颜色改成旧的兜底色
        "m8.TerrainCatalog.java",
        "TerrainCatalog.java",
        [('"#9CCB5B"', '"#6CC261"')],
    ),
    (
        # 造一个退化带：low_hills 的 minHeight == maxHeight
        "m9.TerrainCatalog.java",
        "TerrainCatalog.java",
        [("0.55, 0.65, 2, 1, 1, 2,", "0.55, 0.55, 2, 1, 1, 2,")],
    ),
]

OUT.mkdir(parents=True, exist_ok=True)
ok = True
for name, target, edits in MUTANTS:
    src = (REF / target).read_text(encoding="utf-8")
    text = src
    for needle, repl in edits:
        n = text.count(needle)
        if n != 1:
            print(f"!! {name}: needle 命中 {n} 次（要求 1）：{needle[:60]!r}")
            ok = False
            continue
        text = text.replace(needle, repl)
    if text == src:
        print(f"!! {name}: 替换后与原件完全相同")
        ok = False
        continue
    (OUT / name).write_text(text, encoding="utf-8")
    print(f"OK {name}  edits={len(edits)}  bytes={len(text.encode('utf-8'))} (原件 {len(src.encode('utf-8'))})")

sys.exit(0 if ok else 1)
