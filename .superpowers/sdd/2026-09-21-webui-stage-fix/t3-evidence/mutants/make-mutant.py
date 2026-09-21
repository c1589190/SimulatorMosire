#!/usr/bin/env python3
"""T3 变异体生成器：按 id 对 pristine 文件做一处定点改动，写出同规范名目标文件。

用法：make-mutant.py <id> <pristine 源> <目标路径>
每处替换都断言"恰好命中一次"，否则非零退出（防静默不生效 ⇒ 假存活）。
★ 裁定 42：`jm_merge_replace` 是 M8 T5 的 m2 在**当前字节**上的重放（旧快照已过期，不得整套套用）。
"""
import sys
import pathlib

ROUND, SRC, DST = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
text = SRC.read_text(encoding="utf-8")

REPLACEMENTS = {
    # ── Java ──────────────────────────────────────────────────────────────────
    # jm_apply：between 不把 edges 的差异写进变更集（edges 恒 Unchanged）= 旧仓 MapDiff 不带 edges 的缺陷。
    # ★ 不是"删组件"（那会让 EdgeRef::parse 成为未用 import ⇒ Checkstyle 拦在 surefire 之前 = VOID）；
    #   这是**可编译且 checkstyle 干净**的等价缺陷表达，红点仍落在 C4 的往返/非 Unchanged 断言上。
    "jm_apply": [
        (
            "        FieldDelta.diff(base.edges(), target.edges()));\n",
            "        FieldDelta.diff(base.edges(), base.edges()));\n",
        )
    ],
    # jm_hardcode：kind 词表写回硬编码 river/road ⇒ 自定义 canal 被拒、C34 红。
    "jm_hardcode": [
        (
            "    for (String registered : base.pathwayGroups().keySet()) {\n",
            '    for (String registered : java.util.Set.of("river", "road")) {\n',
        )
    ],
    # jm_allow_all：未注册 kind 恒放行 ⇒ fail-closed 断言红。
    "jm_allow_all": [
        (
            '    throw new IllegalArgumentException("未知连通性类型: " + kind);\n',
            "    return normalized;\n",
        )
    ],
    # jm_dupe：去掉重复注册守卫 ⇒ 静默覆盖组定义、断言红。
    "jm_dupe": [
        (
            "    if (base.pathwayGroups().containsKey(group.id())) {\n"
            '      throw new IllegalArgumentException("组已存在: " + group.id());\n'
            "    }\n",
            "",
        )
    ],
    # jm_merge_replace（M8 T5 的 m2 重放）：merge 当 replace 使 ⇒ merge 保 tag 的断言红。
    "jm_merge_replace": [
        (
            "    if (REPLACE.equals(operation)) {\n",
            "    if (REPLACE.equals(operation) || MERGE.equals(operation)) {\n",
        )
    ],
    # ── JS（map.js）────────────────────────────────────────────────────────────
    # sm_chain_end：去掉"同格 ⇒ 结束"分支 ⇒ 手势状态机断言红（C3）。
    "sm_chain_end": [
        (
            "      if (point.q === anchor.q && point.r === anchor.r) {\n"
            "        ended = true; // 同格 ⇒ 结束\n"
            "        return;\n"
            "      }\n",
            "",
        )
    ],
    # sm_no_canon：EdgeRef 双向归一去掉 ⇒ A→B 与 B→A 各成一条、断言红。
    "sm_no_canon": [
        (
            "    var first = a;\n"
            "    var second = b;\n"
            "    if (a.q > b.q || (a.q === b.q && a.r > b.r)) {\n"
            "      first = b;\n"
            "      second = a;\n"
            "    }\n"
            '    return first.q + "_" + first.r + "|" + second.q + "_" + second.r;\n',
            '    return a.q + "_" + a.r + "|" + b.q + "_" + b.r;\n',
        )
    ],
    # sm_delete_keep：删边计划不排除被删的那条（等于没删）⇒ 删边断言红。
    "sm_delete_keep": [
        (
            "      if (!removed[view.edge]) {\n"
            "        surviving.push(view.edge);\n"
            "      }\n",
            "      surviving.push(view.edge);\n",
        )
    ],
    # sm_hit_inf：命中阈值失效（永远命中最近边）⇒ 12px 阈值断言红。
    "sm_hit_inf": [
        (
            "      if (d <= threshold && (!best || d < best.distance)) {\n",
            "      if (d <= Number.POSITIVE_INFINITY && (!best || d < best.distance)) {\n",
        )
    ],
    # sm_kinds_default：空注册表兜回默认两组 ⇒ fail-closed 断言红。
    "sm_kinds_default": [
        (
            "    registeredEdgeKinds = out;\n",
            "    registeredEdgeKinds = out.length ? out : DEFAULT_EDGE_KINDS.slice();\n",
        )
    ],
    # sm_subtool_default（T2 m4 在当前字节上的重放）：未知工具兜成「地形」⇒ fail-closed 断言红。
    "sm_subtool_default": [
        (
            '    return isRegisteredEdgeKind(tool) ? "connectivity" : null;\n',
            '    return isRegisteredEdgeKind(tool) ? "connectivity" : "terrain";\n',
        )
    ],
    # t2m1：面板可见性去掉互斥（地形恒可见）⇒ 可见性断言红（C1）。
    "t2m1": [("      terrain: subtool === \"terrain\",\n", "      terrain: true,\n")],
    # t2m2：地形线也放行 map.SetEdge（跨线）⇒ C2 的写白名单断言红。
    "t2m2": [
        (
            '    terrain: ["map.SetTerrain", "map.RandomizeRegion"],\n',
            '    terrain: ["map.SetTerrain", "map.RandomizeRegion", "map.SetEdge"],\n',
        )
    ],
    # t2m3：commitEdge 去掉写门 ⇒ 静态门控断言红。
    "t2m3": [
        (
            '    if (!mapEditWriteGate(host.mapEditTool, "map.SetEdge").ok) {\n'
            '      setEdgeStatus("当前不是「连通性」编辑线 ⇒ **未发出任何写命令**。", "warn");\n'
            "      return null;\n"
            "    }\n",
            "",
        )
    ],
    # t2m5：REQUIRED_FILES 漏掉一个测试文件 ⇒ 在册断言红（目标 = gate-contract.test.cjs）。
    "t2m5": [('  "map-edit-suboptions.test.cjs",\n', "")],
}

changed = 0
for old, new in REPLACEMENTS[ROUND]:
    hits = text.count(old)
    if hits != 1:
        sys.exit("replacement matched %d times (want 1): %r" % (hits, old[:70]))
    text = text.replace(old, new)
    changed += 1

DST.write_text(text, encoding="utf-8")
print("mutant=%s replacements=%d" % (ROUND, changed))
