#!/usr/bin/env python3
"""T3 变异体生成器：从**当前**原件出发做精确替换，逐个断言"与原件字节不同"。

★ 只生成、不推送；推送由 mut-round.sh 按白名单（目标类名）完成。
★ 生成失败（任一处替换没命中）必须当场非零退出——静默的"没替换"会让本轮跑的是原件，
   于是"三向全绿"，正是 M2 Task 1 踩过的坑。
"""
import hashlib
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]  # mutants/ → t3-evidence/ → <sdd 段>/ → sdd/ → .superpowers/ → 仓根
while not (ROOT / "simos-unit").is_dir():
    if ROOT.parent == ROOT:
        sys.exit("ABORT: 找不到含 simos-unit 的仓根")
    ROOT = ROOT.parent
OPS = ROOT / "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java"
OFFSET_H = ROOT / "simos-unit/src/main/java/io/mosire/simos/unit/spi/SetFormationOffsetHandler.java"
OUT = pathlib.Path(__file__).resolve().parent


def md5(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def splice(original: str, old: str, new: str, where: str) -> str:
    if original.count(old) != 1:
        sys.exit(f"ABORT: {where} 的替换锚点命中 {original.count(old)} 次（需恰 1 次）")
    return original.replace(old, new)


ops = OPS.read_text(encoding="utf-8")
offset_h = OFFSET_H.read_text(encoding="utf-8")
orig_ops_md5 = md5(ops)
orig_offset_md5 = md5(offset_h)

# ── m1：detach 改成级联（P3 的不对称被抹平） ──────────────────────────
M1_OLD = """    return withUnit(
        state,
        copyFormation(unit, unit.parent(), append(unit.attached(), at, false), unit.offset()));"""
M1_NEW = """    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    for (UnitId member : subtreeOf(state, id, at)) {
      Unit current = state.units().get(member);
      next.put(
          member,
          copyFormation(
              current, current.parent(), append(current.attached(), at, false), current.offset()));
    }
    return new UnitState(next);"""

# ── m2：attach 不级联（只标根节点） ────────────────────────────────
M2_OLD = "    for (UnitId member : subtree) {"
M2_NEW = "    for (UnitId member : List.of(id)) {"

# ── m3：删掉 op 内的成环显式拒 ────────────────────────────────────
M3_OLD = """    if (subtree.contains(parent)) {
      throw new IllegalArgumentException("父单位 " + parent + " 落在 " + id + " 的子树内（含自身）：会成环");
    }
"""
M3_NEW = ""

# ── m4：setOffset 无视入参、一律写空（P2 的偏移语义被抹掉） ─────────────
M4_OLD = """        copyFormation(unit, unit.parent(), unit.attached(), append(unit.offset(), at, offset)));"""
M4_NEW = """        copyFormation(
            unit,
            unit.parent(),
            unit.attached(),
            append(unit.offset(), at, Optional.<RelativeOffset>empty())));"""

# ── m5：部分分量 ⇒ 当成清偏移（handler 层，取代"缺的分量按 0 补"） ────────
M5_OLD = "          dq.isEmpty() && dr.isEmpty()"
M5_NEW = "          dq.isEmpty() || dr.isEmpty()"

mutants = {
    "m1_UnitOperations.java": splice(ops, M1_OLD, M1_NEW, "m1/detachUnit"),
    "m2_UnitOperations.java": splice(ops, M2_OLD, M2_NEW, "m2/attachSubtree"),
    "m3_UnitOperations.java": splice(ops, M3_OLD, M3_NEW, "m3/attachSubtree 环校验"),
    "m4_UnitOperations.java": splice(ops, M4_OLD, M4_NEW, "m4/setOffset"),
    "m5_SetFormationOffsetHandler.java": splice(
        offset_h, M5_OLD, M5_NEW, "m5/SetFormationOffsetHandler"
    ),
}

base = {"m1": orig_ops_md5, "m2": orig_ops_md5, "m3": orig_ops_md5, "m4": orig_ops_md5,
        "m5": orig_offset_md5}
for name, text in mutants.items():
    tag = name.split("_")[0]
    got = md5(text)
    if got == base[tag]:
        sys.exit(f"ABORT: {name} 与原件字节相同 —— 该变异体作废")
    if len(text) == 0:
        sys.exit(f"ABORT: {name} 为空")
    (OUT / name).write_text(text, encoding="utf-8")
    print(f"OK {name} orig={base[tag]} mutant={got}")
