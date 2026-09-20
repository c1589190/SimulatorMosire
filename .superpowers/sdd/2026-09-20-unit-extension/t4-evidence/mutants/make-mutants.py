#!/usr/bin/env python3
"""T4 变异体生成器：从**当前**原件出发做精确替换，逐个断言"与原件字节不同"。

★ 只生成、不推送；推送由 mut-round.sh 按白名单（**目标类名**）完成。
★ 生成失败（任一处替换没命中）必须当场非零退出——静默的"没替换"会让本轮跑的是原件，
   于是"三向全绿"，正是 M2 Task 1 踩过的坑（护栏必须自证 形态 1）。
★ 同时产出 manifest.txt（变异体文件名 <TAB> 目标路径），供 run-all-rounds.sh 按实测清单推——
   不靠手抄路径，避免"推错类名 ⇒ 编译错误 ⇒ 假红"（形态 1 的第二个坑）。

与计划 §三 T4 的 m1~m4 的对应关系（**m1 已按 §3 裁定替换掉**，理由见 t4-report.md）：
  m1 整树迁移只改 root（原样带过后代）      ＝ 计划的 m1（但杀点在**段数**，不是计划写的"值"）
  m2 删 op 内成环显式拒                     ＝ 计划的 m2（杀点是**理由文案**，构造期兜底仍在）
  m3 删合体"同格"相等判据                   ＝ 计划的 m3
  m4 合体不查 MOVING                        ＝ 计划的 m4
  m5 删拆分"在子树内"守卫                   ← 新增（spec §一.5 表的两条独立拒绝理由）
  m6 合体只挂 child 节点、不级联            ← 新增（P3 级联 / §一.5 表"记作 attach"）
  m7 ReparentSubtreeHandler 把可选 parent 当必填 ← 新增（P4 的降根路径）
  m8 删 splitFormation 空名单守卫           ← 新增（就地裁定：空名单是坏命令）
  m9 UnitPayloads 删"必须是数组"形状校验    ← 新增（**预期存活**：判据弱于行为，如实报）
"""
import hashlib
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]  # mutants/ → t4-evidence/ → <sdd 段>/ → sdd/ → .superpowers/ → 仓根
while not (ROOT / "simos-unit").is_dir():
    if ROOT.parent == ROOT:
        sys.exit("ABORT: 找不到含 simos-unit 的仓根")
    ROOT = ROOT.parent

OPS = "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java"
REPARENT_H = "simos-unit/src/main/java/io/mosire/simos/unit/spi/ReparentSubtreeHandler.java"
SPLIT_H = "simos-unit/src/main/java/io/mosire/simos/unit/spi/SplitFormationHandler.java"
MERGE_H = "simos-unit/src/main/java/io/mosire/simos/unit/spi/MergeFormationHandler.java"
PAYLOADS = "simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitPayloads.java"

OUT = pathlib.Path(__file__).resolve().parent


def md5(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def splice(original: str, old: str, new: str, where: str) -> str:
    count = original.count(old)
    if count != 1:
        sys.exit(f"ABORT: {where} 的替换锚点命中 {count} 次（需恰 1 次）")
    return original.replace(old, new)


sources = {p: (ROOT / p).read_text(encoding="utf-8") for p in (OPS, REPARENT_H, SPLIT_H, MERGE_H, PAYLOADS)}
orig_md5 = {p: md5(t) for p, t in sources.items()}

# ── m1：整树迁移只改 root（后代原样带过 ⇒ 后代少一段） ────────────────────
M1_OLD = """      // 只有 root 换父；后代在同一刻重新挂载，值仍是它本来的父（整树一起落到 at）
      Optional<UnitId> parent =
          member.equals(rootId) ? newParent : requiredParentAt(state, member, at);
      next.put(
          member,
          copyFormation(
              current, append(current.parent(), at, parent), current.attached(), current.offset()));"""
M1_NEW = """      // MUTANT m1：只有 root 换父，后代原样带过（计划 §三 T4 的 m1 复原体）
      if (!member.equals(rootId)) {
        next.put(member, current);
        continue;
      }
      next.put(
          member,
          copyFormation(
              current,
              append(current.parent(), at, newParent),
              current.attached(),
              current.offset()));"""

# ── m2：删掉 reparentSubtree 的成环显式拒 ──────────────────────────────
M2_OLD = """    List<UnitId> subtree = subtreeOf(state, rootId, at);
    if (newParent.isPresent() && subtree.contains(newParent.get())) {
      throw new IllegalArgumentException(
          "新父 " + newParent.get() + " 落在 " + rootId + " 的子树内（含自身）：会成环");
    }
"""
M2_NEW = """    List<UnitId> subtree = subtreeOf(state, rootId, at);
    // MUTANT m2：删掉 op 内的成环显式拒（只剩 UnitState 构造期的成环兜底）
"""

# ── m3：删掉合体"同格"判据的相等那一半 ────────────────────────────────
M3_OLD = """    if (!childHex.get().equals(parentHex.get())) {
      throw new IllegalArgumentException(
          "单位 "
              + childId
              + " 在 "
              + at
              + " 位于 "
              + childHex.get()
              + "，与 "
              + parentId
              + " 的 "
              + parentHex.get()
              + " 不同格：只有同格才能合体");
    }
"""
M3_NEW = """    // MUTANT m3：删掉"同格"判据的相等那一半（"不可确定"的那半还在）
"""

# ── m4：合体不查 MOVING（两处：删前置 + 不再持有 child 局部量） ─────────────
M4_OLD_A = """    Unit child = require(state, childId); // 存在性校验
    requireExists(state, parentId);"""
M4_NEW_A = """    require(state, childId); // 存在性校验（MUTANT m4：不再持有 child）
    requireExists(state, parentId);"""
M4_OLD_B = """    if (child.status() != UnitStatus.MOVING) {
      throw new IllegalArgumentException(
          "单位 " + childId + " 的状态是 " + child.status() + " 而不是 MOVING：只有移动中的单位才能合体");
    }
"""
M4_NEW_B = """    // MUTANT m4：删掉"只有 MOVING 才能合体"这条独立前置
"""

# ── m5：删掉拆分"目标必须在 rootId 子树内"的守卫（连局部量一起删，避免未用符号） ──
M5_OLD = """    List<UnitId> subtree = subtreeOf(state, rootId, at);
    Set<UnitId> targets = new LinkedHashSet<>();
    for (UnitId id : subUnitIds) {
      require(state, id); // 存在性校验（缺 id 时先报"不存在"，而不是"不在子树内"）
      if (!subtree.contains(id)) {
        throw new IllegalArgumentException(
            "单位 " + id + " 不在 " + rootId + " 在 " + at + " 的子树内：不能拆分");
      }
      targets.add(id);
    }
"""
M5_NEW = """    Set<UnitId> targets = new LinkedHashSet<>();
    for (UnitId id : subUnitIds) {
      require(state, id); // 存在性校验（缺 id 时先报"不存在"，而不是"不在子树内"）
      // MUTANT m5：删掉"目标必须在 rootId 子树内"这条守卫
      targets.add(id);
    }
"""

# ── m6：合体只重挂 child 节点本身（P3 的 attach 级联被抹掉） ────────────────
M6_OLD = "    return attachSubtree(state, childId, parentId, at);"
M6_NEW = """    // MUTANT m6：只重挂 child 节点本身，不级联（P3 的 attach 级联被抹掉）
    Unit node = state.units().get(childId);
    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.put(
        childId,
        copyFormation(
            node,
            append(node.parent(), at, Optional.of(parentId)),
            append(node.attached(), at, true),
            node.offset()));
    return new UnitState(next);"""

# ── m7：ReparentSubtreeHandler 把可选 parent 当必填（唯一的降根路径被堵死） ──
M7_OLD = """      Optional<UnitId> parent = UnitPayloads.optionalId(payload, "parent");"""
M7_NEW = """      // MUTANT m7：把可选 parent 当必填（照抄 AttachUnitHandler 的 orElseThrow）
      Optional<UnitId> parent =
          Optional.of(
              UnitPayloads.optionalId(payload, "parent")
                  .orElseThrow(() -> new IllegalArgumentException("字段 parent 必填")));"""

# ── m8：删掉 splitFormation 的"空名单"守卫 ────────────────────────────────
M8_OLD = """    if (subUnitIds.isEmpty()) {
      throw new IllegalArgumentException("subUnitIds 不得为空：拆分命令至少要指名一个目标");
    }
"""
M8_NEW = """    // MUTANT m8：删掉"subUnitIds 不得为空"这条守卫（空名单静默变成"什么都不做"）
"""

# ── m9：UnitPayloads 删掉"必须是数组"的形状校验（**预期存活**） ─────────────
M9_OLD = """    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
"""
M9_NEW = """    // MUTANT m9：删掉"必须是数组"的形状校验（对象会被当成空数组走掉）
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
"""

# 逐个变异体 = 从**该路径的原件**出发、按序折叠它的替换清单（每处都断言恰命中 1 次）。
# ★ 构建与核验是**同一次折叠**：绝不用裸 replace 去"再算一遍"——两处写法一旦分叉，
#   核验就退化成比对原件与原件（本次实测：m4 的两段锚点在文件里不连续，裸 replace 静默不命中、
#   于是报"与原件字节相同"）。这正是形态 1 的"先怀疑自己的读取"。
PLAN = {
    "m1_UnitOperations.java": (OPS, [(M1_OLD, M1_NEW, "m1/reparentSubtree")]),
    "m2_UnitOperations.java": (OPS, [(M2_OLD, M2_NEW, "m2/reparentSubtree 环校验")]),
    "m3_UnitOperations.java": (OPS, [(M3_OLD, M3_NEW, "m3/mergeFormation 同格")]),
    "m4_UnitOperations.java": (
        OPS,
        [
            (M4_OLD_A, M4_NEW_A, "m4/mergeFormation child 局部量"),
            (M4_OLD_B, M4_NEW_B, "m4/mergeFormation MOVING"),
        ],
    ),
    "m5_UnitOperations.java": (OPS, [(M5_OLD, M5_NEW, "m5/splitFormation 子树")]),
    "m6_UnitOperations.java": (OPS, [(M6_OLD, M6_NEW, "m6/mergeFormation 级联")]),
    "m7_ReparentSubtreeHandler.java": (
        REPARENT_H,
        [(M7_OLD, M7_NEW, "m7/ReparentSubtreeHandler")],
    ),
    "m8_UnitOperations.java": (OPS, [(M8_OLD, M8_NEW, "m8/splitFormation 空名单")]),
    "m9_UnitPayloads.java": (PAYLOADS, [(M9_OLD, M9_NEW, "m9/UnitPayloads requireTextArray")]),
}

manifest = []
for name, (target, edits) in PLAN.items():
    text = sources[target]
    for old, new, where in edits:
        text = splice(text, old, new, f"{name} · {where}")
    got = md5(text)
    if got == orig_md5[target]:
        sys.exit(f"ABORT: {name} 与原件字节相同 —— 该变异体作废")
    if not text:
        sys.exit(f"ABORT: {name} 为空")
    (OUT / name).write_text(text, encoding="utf-8")
    manifest.append(f"{name}\t{target}")
    print(f"OK {name} target={target} orig={orig_md5[target]} mutant={got}")

(OUT / "manifest.txt").write_text("\n".join(manifest) + "\n", encoding="utf-8")
print(f"OK manifest.txt 行数={len(manifest)}")
