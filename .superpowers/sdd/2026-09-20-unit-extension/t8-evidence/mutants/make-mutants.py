#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""T8 变异体生成器（照 T7 的 make-mutants.py）。

每一处变异用**精确字面量**替换（needle 必须在原件里恰好出现 1 次，否则当场报错退出——
"找不到针"绝不能被静默当成"变异成功"）。生成物落在本目录，**绝不写进源码树**。
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]  # .../uet8
OUT = pathlib.Path(__file__).resolve().parent

OPS = "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java"
HANDLER = "simos-unit/src/main/java/io/mosire/simos/unit/spi/ApplyCasualtiesHandler.java"
BUS = "simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java"

# label, source path, needle, replacement, 变异说明
MUTANTS = [
    (
        "t8m1_UnitOperations",
        OPS,
        "            unit.member() + personnelDelta,",
        "            personnelDelta,",
        "m1：把 Δ 当**绝对值**（覆写）——100 + (−30) 应得 70，变异后得 −30/30",
    ),
    (
        "t8m1b_UnitOperations",
        OPS,
        "            unit.member() + personnelDelta,",
        "            Math.abs(personnelDelta),",
        "m1（值形态）：把 Δ 当**绝对值**（覆写）——100 + (−30) 应得 70，变异后得 30（合法值，故红在**值断言**上）",
    ),
    (
        "t8m2_UnitOperations",
        OPS,
        "    if (personnelDelta < -unit.member()) {\n"
        "      throw new IllegalArgumentException(\n"
        '          "人员战损超出当前值: " + unit.member() + " + (" + personnelDelta + ")");\n'
        "    }\n",
        "",
        "m2：删掉人员上界校验（|Δ| ≤ 当前值）",
    ),
    (
        "t8m3_UnitOperations",
        OPS,
        "      equipment.put(key, current + delta);",
        "      equipment.clear(); // 变异 m3：整表替换装备（未提及键被丢）\n"
        "      equipment.put(key, current + delta);",
        "m3：装备整表替换（未提及键被丢）",
    ),
    (
        "t8m4_UnitOperations",
        OPS,
        "      if (current == null) {\n"
        "        // ★ P14：未知键**拒绝**，不视作 0（\"没有这件装备\"不是\"这件装备是 0\"）。\n"
        '        throw new IllegalArgumentException("未知装备键: " + key);\n'
        "      }\n",
        "      if (current == null) {\n"
        "        continue; // 变异 m4：未知键视作 0 忽略\n"
        "      }\n",
        "m4：未知装备键视作 0 忽略（不拒）",
    ),
    (
        "t8m5_UnitOperations",
        OPS,
        "    next.put(unit.id(), unit); // 覆盖时**保持原键位**（LinkedHashMap 的既有键不改变位置）\n"
        "    return state.withUnits(next);",
        "    next.put(unit.id(), unit); // 覆盖时**保持原键位**（LinkedHashMap 的既有键不改变位置）\n"
        "    return new UnitState(next); // 变异 T5-L4：绕过 withUnits（commandChains 被静默清空）",
        "m5（T5-L4 通则）：`withUnit` 改回 `new UnitState(units)` ⇒ 链被静默清空",
    ),
    (
        "t8m6_ApplyCasualtiesHandler",
        HANDLER,
        "      return new HandlerOutcome.Applied(UnitChangeSet.between(snapshot.state(), next));",
        "      return new HandlerOutcome.Applied(\n"
        "          UnitChangeSet.between(snapshot.state(), snapshot.state())); // 变异 m6：战损不落 revision",
        "m6：变更集恒为空 ⇒ 战损不落 revision（回退/前取都读不到它）",
    ),
    (
        "t8m7_CommandBus",
        BUS,
        '                "payloadDigest", Digest.sha256(envelope.payloadJson()))));',
        '                "payloadDigest", Digest.sha256(envelope.payloadJson()),\n'
        '                "payloadPlain", envelope.payloadJson()))); // 变异 m7：领域载荷明文进事件',
        "m7：把领域载荷明文塞进 received 事件（判据：事件无明文 delta）",
    ),
]


def main() -> int:
    failures = []
    for label, rel, needle, repl, why in MUTANTS:
        src = ROOT / rel
        text = src.read_text(encoding="utf-8")
        hits = text.count(needle)
        if hits != 1:
            failures.append(f"{label}: needle 在 {rel} 里出现 {hits} 次（要求恰 1 次）")
            continue
        out = OUT / f"{label}.java"
        out.write_text(text.replace(needle, repl), encoding="utf-8")
        print(f"OK   {label}.java  ←  {rel}   [{why}]")
    if failures:
        print("\n".join("FAIL " + f for f in failures), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
