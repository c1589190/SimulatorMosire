#!/usr/bin/env python3
"""T5 变异体生成器：从**当前**（T5 落地后的）原件出发做精确替换，逐个断言"与原件字节不同"。

★ 两条纪律（形态 1 + 裁定 T5 的"变异体必须重新生成、不许复用"）：
  ① 只生成、不推送；推送由 mut-round.sh 按白名单（**目标类名**）完成。
  ② 任一处替换没命中 ⇒ 当场非零退出——静默的"没替换"会让本轮跑的是原件，于是"三向全绿"。
  ③ **T3/T4 的变异体在本轮一律重新生成**（它们的靶文件 `UnitOperations.java` / `UnitPayloads.java`
     已被 T5 改过字节）——复用旧文件 = 把 T5 的修复连同变异意图一起回滚，红绿都失去意义。
     靶文件字节**未变**的（`ReparentSubtreeHandler.java` / `SetFormationOffsetHandler.java`）
     也照样重新生成：产出 md5 与 T3/T4 那轮逐字节相同本身就是"同一份字节"的证据。

★ 每个变异体自带 MUST_CONTAIN / MUST_NOT_CONTAIN 断言：
  - T5-U2 的五个"还原成 1 参兼容构造器"变异体（site 1/2/3/4/5）**必须**出现 `new UnitState(`，
    且**不得**在该处留下 `withUnits`——这是"变异体不含 T5 的修复"的自证（裁定 T5 第 (2) 条）。
"""
import hashlib
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]  # mutants/ → t5-evidence/ → <sdd 段>/ → sdd/ → .superpowers/ → 仓根
while not (ROOT / "simos-unit").is_dir():
    if ROOT.parent == ROOT:
        sys.exit("ABORT: 找不到含 simos-unit 的仓根")
    ROOT = ROOT.parent

OPS = "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java"
REPARENT_H = "simos-unit/src/main/java/io/mosire/simos/unit/spi/ReparentSubtreeHandler.java"
OFFSET_H = "simos-unit/src/main/java/io/mosire/simos/unit/spi/SetFormationOffsetHandler.java"
PAYLOADS = "simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitPayloads.java"
CODEC = "simos-unit/src/main/java/io/mosire/simos/unit/codec/UnitCodec.java"
PARTICIPANT = "simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java"

OUT = pathlib.Path(__file__).resolve().parent


def md5(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def splice(original: str, old: str, new: str, where: str) -> str:
    count = original.count(old)
    if count != 1:
        sys.exit(f"ABORT: {where} 的替换锚点命中 {count} 次（需恰 1 次）")
    return original.replace(old, new)


sources = {
    p: (ROOT / p).read_text(encoding="utf-8")
    for p in (OPS, REPARENT_H, OFFSET_H, PAYLOADS, CODEC, PARTICIPANT)
}
orig_md5 = {p: md5(t) for p, t in sources.items()}

# ══════════════════════════════════════════════════════════════════
# A. T3 的五个变异体（**重新生成**，靶文件字节已变）
# ══════════════════════════════════════════════════════════════════

# ── T3-m1：detach 改成级联（P3 的不对称被抹平） ──────────────────────────
T3M1_OLD = """    return withUnit(
        state,
        copyFormation(unit, unit.parent(), append(unit.attached(), at, false), unit.offset()));"""
T3M1_NEW = """    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    for (UnitId member : subtreeOf(state, id, at)) {
      Unit current = state.units().get(member);
      next.put(
          member,
          copyFormation(
              current, current.parent(), append(current.attached(), at, false), current.offset()));
    }
    return new UnitState(next);"""

# ── T3-m2：attach 不级联（只标根节点） ────────────────────────────────
# ★ 锚点必须**加长**：T5 之后 `    for (UnitId member : subtree) {` 在文件里命中 **2 次**
#   （attachSubtree 与 reparentSubtree）——短锚点会让 splice 当场 ABORT（这里保留注释行区分）。
T3M2_OLD = """
    for (UnitId member : subtree) {
      Unit current = state.units().get(member);
      // 只有根换父：后代的 parent 原样带过
"""
T3M2_NEW = """
    for (UnitId member : List.of(id)) {
      Unit current = state.units().get(member);
      // 只有根换父：后代的 parent 原样带过（MUTANT T3-m2：不级联）
"""

# ── T3-m3：删掉 op 内的成环显式拒 ────────────────────────────────────
T3M3_OLD = """    if (subtree.contains(parent)) {
      throw new IllegalArgumentException("父单位 " + parent + " 落在 " + id + " 的子树内（含自身）：会成环");
    }
"""
T3M3_NEW = """    // MUTANT T3-m3：删掉 attach 的成环显式拒（只剩 UnitState 构造期的成环兜底）
"""

# ── T3-m4：setOffset 无视入参、一律写空 ────────────────────────────────
T3M4_OLD = """        copyFormation(unit, unit.parent(), unit.attached(), append(unit.offset(), at, offset)));"""
T3M4_NEW = """        copyFormation(
            unit,
            unit.parent(),
            unit.attached(),
            append(unit.offset(), at, Optional.<RelativeOffset>empty())));"""

# ── T3-m5：部分分量 ⇒ 当成清偏移（handler 层） ──────────────────────────
T3M5_OLD = "          dq.isEmpty() && dr.isEmpty()"
T3M5_NEW = "          dq.isEmpty() || dr.isEmpty()"

# ══════════════════════════════════════════════════════════════════
# B. T4 的十个变异体（**重新生成**）
# ══════════════════════════════════════════════════════════════════

# ── T4-m1：整树迁移只改 root（后代原样带过 ⇒ 后代少一段） ────────────────
T4M1_OLD = """      // 只有 root 换父；后代在同一刻重新挂载，值仍是它本来的父（整树一起落到 at）
      Optional<UnitId> parent =
          member.equals(rootId) ? newParent : requiredParentAt(state, member, at);
      next.put(
          member,
          copyFormation(
              current, append(current.parent(), at, parent), current.attached(), current.offset()));"""
T4M1_NEW = """      // MUTANT T4-m1：只有 root 换父，后代原样带过
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

# ── T4-m2：删掉 reparentSubtree 的成环显式拒 ──────────────────────────
T4M2_OLD = """    if (newParent.isPresent() && subtree.contains(newParent.get())) {
      throw new IllegalArgumentException(
          "新父 " + newParent.get() + " 落在 " + rootId + " 的子树内（含自身）：会成环");
    }
"""
T4M2_NEW = """    // MUTANT T4-m2：删掉 reparentSubtree 的成环显式拒（只剩构造期兜底）
"""

# ── T4-m3：删掉合体"同格"判据的相等那一半 ────────────────────────────
T4M3_OLD = """    if (!childHex.get().equals(parentHex.get())) {
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
T4M3_NEW = """    // MUTANT T4-m3：删掉"同格"判据的相等那一半（"不可确定"的那半还在）
"""

# ── T4-m4：合体不查 MOVING ───────────────────────────────────────────
T4M4_OLD_A = """    Unit child = require(state, childId); // 存在性校验
    requireExists(state, parentId);"""
T4M4_NEW_A = """    require(state, childId); // 存在性校验（MUTANT T4-m4：不再持有 child）
    requireExists(state, parentId);"""
T4M4_OLD_B = """    if (child.status() != UnitStatus.MOVING) {
      throw new IllegalArgumentException(
          "单位 " + childId + " 的状态是 " + child.status() + " 而不是 MOVING：只有移动中的单位才能合体");
    }
"""
T4M4_NEW_B = """    // MUTANT T4-m4：删掉"只有 MOVING 才能合体"这条独立前置
"""

# ── T4-m5：删掉拆分"目标必须在 rootId 子树内"的守卫 ──────────────────────
T4M5_OLD = """    List<UnitId> subtree = subtreeOf(state, rootId, at);
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
T4M5_NEW = """    Set<UnitId> targets = new LinkedHashSet<>();
    for (UnitId id : subUnitIds) {
      require(state, id); // 存在性校验（缺 id 时先报"不存在"，而不是"不在子树内"）
      // MUTANT T4-m5：删掉"目标必须在 rootId 子树内"这条守卫
      targets.add(id);
    }
"""

# ── T4-m6：合体只重挂 child 节点本身（P3 的 attach 级联被抹掉） ─────────────
T4M6_OLD = "    return attachSubtree(state, childId, parentId, at);"
T4M6_NEW = """    // MUTANT T4-m6：只重挂 child 节点本身，不级联（P3 的 attach 级联被抹掉）
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

# ── T4-m7：ReparentSubtreeHandler 把可选 parent 当必填（唯一的降根路径被堵死） ──
T4M7_OLD = """      Optional<UnitId> parent = UnitPayloads.optionalId(payload, "parent");"""
T4M7_NEW = """      // MUTANT m7：把可选 parent 当必填（照抄 AttachUnitHandler 的 orElseThrow）
      Optional<UnitId> parent =
          Optional.of(
              UnitPayloads.optionalId(payload, "parent")
                  .orElseThrow(() -> new IllegalArgumentException("字段 parent 必填")));"""

# ── T4-m8：删掉 splitFormation 的"空名单"守卫 ────────────────────────────
T4M8_OLD = """    if (subUnitIds.isEmpty()) {
      throw new IllegalArgumentException("subUnitIds 不得为空：拆分命令至少要指名一个目标");
    }
"""
T4M8_NEW = """    // MUTANT T4-m8：删掉"subUnitIds 不得为空"这条守卫（空名单静默变成"什么都不做"）
"""

# ── T4-m9：UnitPayloads 删掉"必须是数组"的形状校验（**预期存活**） ─────────────
T4M9_OLD = """    if (value == null || value.isNull() || !value.isArray()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
"""
T4M9_NEW = """    // MUTANT T4-m9：删掉"必须是数组"的形状校验（对象会被当成空数组走掉）
    if (value == null || value.isNull()) {
      throw new IllegalArgumentException("字段 " + field + " 必须是 [字符串…] 数组: " + payload);
    }
"""

# ══════════════════════════════════════════════════════════════════
# C. T5 自己的十二个变异体
# ══════════════════════════════════════════════════════════════════

# ── t5m01：多属被拒（P11 的"多属正常态"被抹掉） ───────────────────────────
T5M01_OLD = "    requireChainMembersResolve(state, chain.id(), chain.commander(), chain.members());\n"
T5M01_NEW = """    requireChainMembersResolve(state, chain.id(), chain.commander(), chain.members());
    // MUTANT t5m01：多属被拒（P11 / spec §一.2 的"多属是正常态"被抹掉）
    for (CommandChain other : state.commandChains().values()) {
      for (UnitId member : chain.members()) {
        if (other.members().contains(member)) {
          throw new IllegalArgumentException("单位 " + member + " 已在链 " + other.id() + " 里：一单位只属一链");
        }
      }
    }
"""

# ── t5m02：updateChain 未给 members 时**不**带上既有成员（静默缩水成 {commander}） ──
T5M02_OLD = """    } else {
      nextMembers.addAll(existing.members()); // 未给 ⇒ 不动（不是清空）
    }
"""
T5M02_NEW = """    } else {
      // MUTANT t5m02：忘了把既有 members 带过来，只补了 commander ⇒ 静默缩水
      nextMembers.add(existing.commander());
    }
"""

# ── t5m03：删掉 CommandChainId 的 Map 键反序列化器（UnitCodec:57） ──────────
# ★ 必须**连 import 一起删**：删掉那行后 `import io.mosire.simos.unit.CommandChainId;`（:8）就成了
#   未用 import，而 Checkstyle 的 UnusedImports 跑在 surefire **之前** ⇒ 整轮在 checkstyle 处就 BUILD
#   FAILURE、**根本没有 surefire 报告**（T5 首轮实测：gate ⑥ 判 VOID）。删 import 是**同一个变异意图**
#   的一部分（不留悬空 import），不是第二处语义改动。
T5M03_OLD = "    module.addKeyDeserializer(CommandChainId.class, keyDeserializer(CommandChainId::parse));\n"
T5M03_NEW = "    // MUTANT t5m03：删掉 CommandChainId 的 Map 键反序列化器（死在类型解析期）\n"
T5M03_OLD_IMPORT = "import io.mosire.simos.unit.CommandChainId;\n"
T5M03_NEW_IMPORT = ""

# ── t5m04：site 4（面最宽）——withUnit 还原成 1 参兼容构造器 ────────────────
T5M04_OLD = """    next.put(unit.id(), unit); // 覆盖时**保持原键位**（LinkedHashMap 的既有键不改变位置）
    return state.withUnits(next);"""
T5M04_NEW = """    next.put(unit.id(), unit); // 覆盖时**保持原键位**（LinkedHashMap 的既有键不改变位置）
    // MUTANT t5m04：还原成 1 参兼容构造器（T5-U2 的 site 4，十个单单位操作都经此）
    return new UnitState(next);"""

# ── t5m05：site 5——UnitTimeParticipant 还原成 1 参兼容构造器 ───────────────
T5M05_OLD = """    // ★ T5-U2：用 withUnits 保留 commandChains——推进改的是 position/movement，链与它无关（旧写法 new UnitState(units)
    // 会把链静默抹掉）
    UnitState target = snapshot.state().withUnits(units);"""
T5M05_NEW = """    // MUTANT t5m05：还原成 1 参兼容构造器（T5-U2 的 site 5）——链被静默抹掉
    UnitState target = new UnitState(units);"""

# ── t5m06：site 1——disband 还原成 1 参兼容构造器 ──────────────────────────
T5M06_OLD = """    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.remove(id);
    return state.withUnits(next);"""
T5M06_NEW = """    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.remove(id);
    // MUTANT t5m06：还原成 1 参兼容构造器（T5-U2 的 site 1）——链被静默清空
    return new UnitState(next);"""

# ── t5m07：删掉 disband 的链前置（连同私有助手一起删，避免未用符号） ───────────
T5M07_OLD_A = "    requireNotInAnyChain(state, id);\n"
T5M07_NEW_A = "    // MUTANT t5m07：删掉 disband 的链前置\n"
T5M07_OLD_B = """  /** 链引用的可读理由（T5 / spec §一.2 不变量 2）：`id` 是任何一条链的 commander 或 member ⇒ 拒。 */
  private static void requireNotInAnyChain(UnitState state, UnitId id) {
    for (CommandChain chain : state.commandChains().values()) {
      if (chain.commander().equals(id)) {
        throw new IllegalArgumentException(
            "单位 " + id + " 仍是链 " + chain.id() + " 的 commander：先改链、再解散");
      }
      if (chain.members().contains(id)) {
        throw new IllegalArgumentException("单位 " + id + " 仍是链 " + chain.id() + " 的成员：先改链、再解散");
      }
    }
  }

"""
T5M07_NEW_B = ""

# ── t5m08：site 2——attachSubtree 还原成 1 参兼容构造器 ───────────────────
T5M08_OLD = """      next.put(
          member,
          copyFormation(current, parents, append(current.attached(), at, true), current.offset()));
    }
    return state.withUnits(next);"""
T5M08_NEW = """      next.put(
          member,
          copyFormation(current, parents, append(current.attached(), at, true), current.offset()));
    }
    // MUTANT t5m08：还原成 1 参兼容构造器（T5-U2 的 site 2）——链被静默清空
    return new UnitState(next);"""

# ── t5m09：site 3——reparentSubtree 还原成 1 参兼容构造器 ─────────────────
T5M09_OLD = """      next.put(
          member,
          copyFormation(
              current, append(current.parent(), at, parent), current.attached(), current.offset()));
    }
    return state.withUnits(next);"""
T5M09_NEW = """      next.put(
          member,
          copyFormation(
              current, append(current.parent(), at, parent), current.attached(), current.offset()));
    }
    // MUTANT t5m09：还原成 1 参兼容构造器（T5-U2 的 site 3）——链被静默清空
    return new UnitState(next);"""

# ── t5m10：删掉 updateChain 的"生效 commander 必须在生效 members 里" ─────────
T5M10_OLD = """    if (!nextMembers.contains(nextCommander)) {
      throw new IllegalArgumentException(
          "链 " + id + " 的 commander " + nextCommander + " 不在 members 内：先把它加进 members 再改链");
    }
"""
T5M10_NEW = """    // MUTANT t5m10：删掉"生效 commander 必须在生效 members 里"（由 CommandChain 构造期兜底）
"""

# ── t5m11：删掉 createChain 的重复 id 检查 ─────────────────────────────
T5M11_OLD = """    if (state.commandChains().containsKey(chain.id())) {
      throw new IllegalArgumentException("链 id 已存在: " + chain.id());
    }
"""
T5M11_NEW = """    // MUTANT t5m11：删掉"链 id 已存在"的检查（后建的静默覆盖先建的）
"""

# ── t5m12：删掉 createChain 的引用解析检查（只剩构造期兜底、消息刻意不同） ──────
T5M12_OLD = "    requireChainMembersResolve(state, chain.id(), chain.commander(), chain.members());\n"
T5M12_NEW = "    // MUTANT t5m12：删掉 createChain 的引用解析检查（只剩 UnitState 构造期兜底）\n"

# ── t5m13：**改值**型按键变异（证明往返守卫在"键这条路径"上有判别力） ────────────
# ★ 由 t5m03 的实测逼出来：删掉 CommandChainId 那行**存活**（Jackson 对单 String record 有默认键路径）
#   ⇒ 该行不是承重的、t5m03 是**等价变异体**。要证"这条判据不是装饰"，就得让键**真的变成别的值**。
T5M13_OLD = "    module.addKeyDeserializer(CommandChainId.class, keyDeserializer(CommandChainId::parse));\n"
T5M13_NEW = """    module.addKeyDeserializer(
        CommandChainId.class,
        keyDeserializer(text -> new CommandChainId("k-" + text))); // MUTANT t5m13
"""

# ── t5m14：整个键注册面拿掉（连 import 与私有助手一起，避免留下未用符号） ──────────
T5M14_OLD_BLOCK = """  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("unit-json-keys");
    module.addKeyDeserializer(UnitId.class, keyDeserializer(UnitId::parse));
    module.addKeyDeserializer(CommandChainId.class, keyDeserializer(CommandChainId::parse));
    return module;
  }

  private static <K> KeyDeserializer keyDeserializer(Function<String, K> parse) {
    return new KeyDeserializer() {
      @Override
      public Object deserializeKey(String key, DeserializationContext context) {
        return parse.apply(key);
      }
    };
  }

"""
T5M14_NEW_BLOCK = """  private static SimpleModule keyModule() {
    SimpleModule module = new SimpleModule("unit-json-keys");
    // MUTANT t5m14：两个键反序列化器**整个**不注册（测这两个 record 键是否真的承重于本注册）
    return module;
  }

"""
T5M14_UNUSED_IMPORTS = (
    "import com.fasterxml.jackson.databind.DeserializationContext;\n",
    "import com.fasterxml.jackson.databind.KeyDeserializer;\n",
    "import io.mosire.simos.unit.CommandChainId;\n",
    "import io.mosire.simos.unit.UnitId;\n",
    "import java.util.function.Function;\n",
)


# 构建与核验是**同一次折叠**（绝不用裸 replace 再算一遍——两处写法一旦分叉，核验就退化成
# 比对原件与原件）。每个条目 = (目标路径, [(old, new, where)], must_contain, must_not_contain)。
PLAN = {
    # ── A. T3 重新生成 ──
    "t3m1_UnitOperations.java": (OPS, [(T3M1_OLD, T3M1_NEW, "T3-m1/detachUnit")], [], []),
    "t3m2_UnitOperations.java": (OPS, [(T3M2_OLD, T3M2_NEW, "T3-m2/attachSubtree 级联")], [], []),
    "t3m3_UnitOperations.java": (OPS, [(T3M3_OLD, T3M3_NEW, "T3-m3/attach 环校验")], [], []),
    "t3m4_UnitOperations.java": (OPS, [(T3M4_OLD, T3M4_NEW, "T3-m4/setOffset")], [], []),
    "t3m5_SetFormationOffsetHandler.java": (
        OFFSET_H,
        [(T3M5_OLD, T3M5_NEW, "T3-m5/SetFormationOffsetHandler")],
        [],
        [],
    ),
    # ── B. T4 重新生成 ──
    "t4m1_UnitOperations.java": (OPS, [(T4M1_OLD, T4M1_NEW, "T4-m1/reparentSubtree")], [], []),
    "t4m2_UnitOperations.java": (OPS, [(T4M2_OLD, T4M2_NEW, "T4-m2/reparent 环校验")], [], []),
    "t4m3_UnitOperations.java": (OPS, [(T4M3_OLD, T4M3_NEW, "T4-m3/merge 同格")], [], []),
    "t4m4_UnitOperations.java": (
        OPS,
        [
            (T4M4_OLD_A, T4M4_NEW_A, "T4-m4/child 局部量"),
            (T4M4_OLD_B, T4M4_NEW_B, "T4-m4/MOVING"),
        ],
        [],
        [],
    ),
    "t4m5_UnitOperations.java": (OPS, [(T4M5_OLD, T4M5_NEW, "T4-m5/split 子树")], [], []),
    "t4m6_UnitOperations.java": (OPS, [(T4M6_OLD, T4M6_NEW, "T4-m6/merge 级联")], [], []),
    "t4m7_ReparentSubtreeHandler.java": (
        REPARENT_H,
        [(T4M7_OLD, T4M7_NEW, "T4-m7/ReparentSubtreeHandler")],
        [],
        [],
    ),
    "t4m8_UnitOperations.java": (OPS, [(T4M8_OLD, T4M8_NEW, "T4-m8/split 空名单")], [], []),
    "t4m9_UnitPayloads.java": (
        PAYLOADS,
        [(T4M9_OLD, T4M9_NEW, "T4-m9/requireTextArray")],
        [],
        [],
    ),
    # ── C. T5 ──
    "t5m01_UnitOperations.java": (
        OPS,
        [(T5M01_OLD, T5M01_NEW, "t5m01/createChain 多属")],
        [# 变异体真的把"多属"变成拒绝
            "一单位只属一链",
            # ★ 变异体**不得**含 T5 的修复被误伤：createChain 仍走 withCommandChains
            "return state.withCommandChains(next);",
        ],
        [],
    ),
    "t5m02_UnitOperations.java": (
        OPS,
        [(T5M02_OLD, T5M02_NEW, "t5m02/updateChain 未给 members")],
        ["nextMembers.add(existing.commander());"],
        [],
    ),
    "t5m03_UnitCodec.java": (
        CODEC,
        [
            (T5M03_OLD, T5M03_NEW, "t5m03/CommandChainId 键反序列化器"),
            (T5M03_OLD_IMPORT, T5M03_NEW_IMPORT, "t5m03/悬空的 import"),
        ],
        ["module.addKeyDeserializer(UnitId.class, keyDeserializer(UnitId::parse));"],
        ["module.addKeyDeserializer(CommandChainId.class", "import io.mosire.simos.unit.CommandChainId;"],
    ),
    "t5m04_UnitOperations.java": (
        OPS,
        [(T5M04_OLD, T5M04_NEW, "t5m04/site 4 withUnit")],
        ["return new UnitState(next);"],
        [],
    ),
    "t5m05_UnitTimeParticipant.java": (
        PARTICIPANT,
        [(T5M05_OLD, T5M05_NEW, "t5m05/site 5 participant")],
        ["UnitState target = new UnitState(units);"],
        ["withUnits(units)"],
    ),
    "t5m06_UnitOperations.java": (
        OPS,
        [(T5M06_OLD, T5M06_NEW, "t5m06/site 1 disband")],
        ["next.remove(id);\n    // MUTANT t5m06", "return new UnitState(next);"],
        [],
    ),
    "t5m07_UnitOperations.java": (
        OPS,
        [(T5M07_OLD_A, T5M07_NEW_A, "t5m07/disband 链前置"), (T5M07_OLD_B, T5M07_NEW_B, "t5m07/助手本体")],
        [],
        ["requireNotInAnyChain", "先改链"],
    ),
    "t5m08_UnitOperations.java": (
        OPS,
        [(T5M08_OLD, T5M08_NEW, "t5m08/site 2 attachSubtree")],
        ["return new UnitState(next);"],
        [],
    ),
    "t5m09_UnitOperations.java": (
        OPS,
        [(T5M09_OLD, T5M09_NEW, "t5m09/site 3 reparentSubtree")],
        ["return new UnitState(next);"],
        [],
    ),
    "t5m10_UnitOperations.java": (
        OPS,
        [(T5M10_OLD, T5M10_NEW, "t5m10/updateChain commander∈members")],
        [],
        ["不在 members 内：先把它加进 members 再改链"],
    ),
    "t5m11_UnitOperations.java": (
        OPS,
        [(T5M11_OLD, T5M11_NEW, "t5m11/createChain 重复 id")],
        [],
        [# ★ 判据必须钉**代码**、不能钉文案：变异体注释里正当地写着"删掉『链 id 已存在』的检查"，
            #   拿消息原文当 needle 会被自己的注释命中 ⇒ 改成钉那行 throw 本身
            "throw new IllegalArgumentException(\"链 id 已存在: \"",
        ],
    ),
    "t5m12_UnitOperations.java": (
        OPS,
        [(T5M12_OLD, T5M12_NEW, "t5m12/createChain 引用解析")],
        [# 助手本体仍在（updateChain 还在用）——删的只是 createChain 里那一次调用
            "private static void requireChainMembersResolve(",
        ],
        ["chain.id(), chain.commander(), chain.members()"],
    ),
    "t5m13_UnitCodec.java": (
        CODEC,
        [(T5M13_OLD, T5M13_NEW, "t5m13/键值改写")],
        ['keyDeserializer(text -> new CommandChainId("k-" + text))'],
        [],
    ),
    "t5m14_UnitCodec.java": (
        CODEC,
        [(T5M14_OLD_BLOCK, T5M14_NEW_BLOCK, "t5m14/keyModule 与私有助手")]
        + [(imp, "", f"t5m14/未用 import: {imp.strip()}") for imp in T5M14_UNUSED_IMPORTS],
        ["SimosObjectMapper.create(keyModule())", '"unit-json-keys"'],
        ["addKeyDeserializer", "import com.fasterxml.jackson.databind.KeyDeserializer;"],
    ),
}

# ★ 五个 T5-U2 的"还原"变异体：撤掉的正是 T5 的修复 ⇒ 必须出现 `new UnitState(`，
#   且**该处**不得再留 `withUnits`。逐个断言"site N 的修复真的不在变异体里"。
SITE_REVERTS = {
    "t5m04_UnitOperations.java": ("site 4 withUnit", OPS),
    "t5m05_UnitTimeParticipant.java": ("site 5 participant", PARTICIPANT),
    "t5m06_UnitOperations.java": ("site 1 disband", OPS),
    "t5m08_UnitOperations.java": ("site 2 attachSubtree", OPS),
    "t5m09_UnitOperations.java": ("site 3 reparentSubtree", OPS),
}

manifest = []
for name, (target, edits, must_contain, must_not_contain) in PLAN.items():
    text = sources[target]
    for old, new, where in edits:
        text = splice(text, old, new, f"{name} · {where}")
    got = md5(text)
    if got == orig_md5[target]:
        sys.exit(f"ABORT: {name} 与原件字节相同 —— 该变异体作废")
    if not text:
        sys.exit(f"ABORT: {name} 为空")
    for needle in must_contain:
        if needle not in text:
            sys.exit(f"ABORT: {name} 缺少必须出现的片段: {needle!r}")
    for needle in must_not_contain:
        if needle in text:
            sys.exit(f"ABORT: {name} 仍含必须消失的片段: {needle!r}")
    if name in SITE_REVERTS:
        where, src = SITE_REVERTS[name]
        if "new UnitState(" not in text:
            sys.exit(f"ABORT: {name} 没有真的还原（找不到 new UnitState(）")
        if text.count("new UnitState(") != 1:
            sys.exit(f"ABORT: {name} 的 new UnitState( 出现 {text.count('new UnitState(')} 次（应恰 1 次）")
        print(f"SITE-REVERT {name} ({where}) new_UnitState=1 orig_new_UnitState=0")
    (OUT / name).write_text(text, encoding="utf-8")
    manifest.append(f"{name}\t{target}")
    print(f"OK {name} target={target} orig={orig_md5[target]} mutant={got}")

(OUT / "manifest.txt").write_text("\n".join(manifest) + "\n", encoding="utf-8")
print(f"OK manifest.txt 行数={len(manifest)}")
