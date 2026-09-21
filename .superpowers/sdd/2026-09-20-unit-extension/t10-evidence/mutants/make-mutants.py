#!/usr/bin/env python3
# T10 变异体生成器：从**当前树**的源文件派生全部变异体（含裁定 42 要求的既有轮重派生）。
# 每处替换都断言"恰好命中一次"（命中 0 或 >1 立即失败）——避免 T5-L5 的"整份文件计数"型假绿。
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]  # .claude/worktrees/uet10
OUT = pathlib.Path(__file__).resolve().parent


def mutate(name: str, rel: str, replacements):
    src = (ROOT / rel).read_text(encoding="utf-8")
    out = src
    for old, new in replacements:
        hits = out.count(old)
        if hits != 1:
            sys.exit(f"ABORT {name}: 片段命中 {hits} 次（要求恰 1）: {old[:60]!r}")
        out = out.replace(old, new)
    if out == src:
        sys.exit(f"ABORT {name}: 变异后与原件逐字节相同")
    (OUT / name).write_text(out, encoding="utf-8")
    print(f"{name}\t{rel}\t{len(src)}->{len(out)}B")


# ── 新护栏 ────────────────────────────────────────────────────────────
# t10m1：RelativeOffset 溢出守卫 ⇒ 退回裸加法（静默回绕）。
mutate(
    "t10m1_RelativeOffset.java",
    "simos-unit/src/main/java/io/mosire/simos/unit/RelativeOffset.java",
    [
        (
            """    try {
      return new HexCoord(Math.addExact(hex.q(), dq), Math.addExact(hex.r(), dr));
    } catch (ArithmeticException e) {
      throw new IllegalArgumentException(
          "偏移叠加溢出 int：hex=" + hex + " offset=(" + dq + "," + dr + ")", e);
    }""",
            """    return new HexCoord(hex.q() + dq, hex.r() + dr);""",
        )
    ],
)

# t10m2：CatalogTool 载荷提示表删一条 ⇒ 构造期拒绝（缺项不再静默）。
mutate(
    "t10m2_CatalogTool.java",
    "simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java",
    [
        (
            '          Map.entry("unit.ApplyCasualties", "id, personnel(负增量), equipment{键:负增量}"),\n',
            "",
        )
    ],
)

# t10m3：工作台写白名单删掉 unit.PlanRoute ⇒ 静态扫描守卫红。
mutate(
    "t10m3_modes.js",
    "simos-app/src/main/resources/webui/modes.js",
    [('        "unit.PlanRoute",\n', "")],
)

# t10m4：disband 顺手清他单位的悬空 rejoinTarget ⇒ T10-d 判据红。
mutate(
    "t10m4_UnitOperations.java",
    "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java",
    [
        (
            """    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.remove(id);
    return state.withUnits(next);
  }""",
            """    Map<UnitId, Unit> next = new LinkedHashMap<>(state.units());
    next.remove(id);
    Map<UnitId, Unit> cleared = new LinkedHashMap<>();
    for (Unit other : next.values()) {
      cleared.put(
          other.id(),
          other.rejoinTarget().filter(id::equals).isPresent()
              ? withRejoinTarget(other, Optional.empty())
              : other);
    }
    return state.withUnits(cleared);
  }""",
        )
    ],
)

# t10m5：mergeFormation 删同格校验 ⇒ e2e 判据 #5 红。
mutate(
    "t10m5_UnitOperations.java",
    "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java",
    [
        (
            """    if (!childHex.get().equals(parentHex.get())) {
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
""",
            "",
        )
    ],
)

# t10m6：reparentSubtree 只处理 root（后代不追加 parent 段）⇒ e2e 判据 #6 红。
mutate(
    "t10m6_UnitOperations.java",
    "simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java",
    [
        (
            """    for (UnitId member : subtree) {
      Unit current = state.units().get(member);
      // 只有 root 换父""",
            """    for (UnitId member : List.of(rootId)) {
      Unit current = state.units().get(member);
      // 只有 root 换父""",
        )
    ],
)

# ── 既有轮重派生（裁定 42：改了 Shell / UnitPayloads ⇒ 旧变异体必须从新字节重新派生）──
# t9m1（删 SetStatus 注册，连未用 import）。
mutate(
    "t10r-t9m1_Shell.java",
    "simos-app/src/main/java/io/mosire/simos/app/Shell.java",
    [
        ("import io.mosire.simos.unit.spi.SetStatusHandler;\n", ""),
        ("            new SetStatusHandler(),\n", ""),
    ],
)

# t9m2（把注册移出 List.of，改在 commandTypes 循环之后）。
mutate(
    "t10r-t9m2_Shell.java",
    "simos-app/src/main/java/io/mosire/simos/app/Shell.java",
    [
        ("            new SetStatusHandler(),\n", ""),
        (
            "\n    // ★ T10-h：participant 由**清单**注册",
            "\n    coreSimos.register(new SetStatusHandler());\n\n    // ★ T10-h：participant 由**清单**注册",
        ),
    ],
)

# t9m3（PlanSparseRoute 注入恒不可通行的匿名 MovementCost）。
mutate(
    "t10r-t9m3_Shell.java",
    "simos-app/src/main/java/io/mosire/simos/app/Shell.java",
    [
        (
            "            new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE),",
            """            new PlanSparseRouteHandler(
                new io.mosire.simos.unit.move.MovementCost() {
                  @Override
                  public java.util.OptionalLong costMillis(
                      io.mosire.simos.map.hex.HexCoord from,
                      io.mosire.simos.map.hex.HexCoord to,
                      io.mosire.simos.unit.Unit unit,
                      io.mosire.simos.map.GameMap map) {
                    return java.util.OptionalLong.empty();
                  }

                  @Override
                  public long minStepCostMillis(
                      io.mosire.simos.unit.Unit unit, io.mosire.simos.map.GameMap map) {
                    return 0L;
                  }
                }),""",
        )
    ],
)

# t4m9（requireTextArray 删"必须是数组"形状校验；T4 原轮在 T5 前，本次只重放其语义改动）。
mutate(
    "t10r-t4m9_UnitPayloads.java",
    "simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitPayloads.java",
    [
        (
            """  static List<String> requireTextArray(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull() || !value.isArray()) {""",
            """  static List<String> requireTextArray(JsonNode payload, String field) {
    JsonNode value = payload.get(field);
    if (value == null || value.isNull()) {""",
        )
    ],
)
