#!/usr/bin/env python3
"""T9 变异体生成器：读当前 Shell.java，写出三个变异体，并**逐条断言替换确实发生**。

m1（任务靶子：删一条注册）：删掉 handlers 列表里的 `new SetStatusHandler(),`（连同随之未用的
    import，否则 Checkstyle 会先于 surefire 拦下 ⇒ 该轮判 VOID）⇒ catalog 29。
m2（HANDOFF §三.2 的唯一真实路径）：把该注册**移出 `List.of(...)`**、改在循环之后
    `coreSimos.register(new SetStatusHandler());` ⇒ handler 注册了、catalog 却漏了。
m3（计划 m2：注入不可通行成本）：`PlanSparseRouteHandler` 换成恒返回空的匿名 `MovementCost`
    ⇒ `unit.PlanSparseRoute` 命令期被拒 ⇒ 覆盖率用例在该步红。
"""

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[5]
SRC = ROOT / "simos-app/src/main/java/io/mosire/simos/app/Shell.java"
OUT = pathlib.Path(__file__).resolve().parent
ORIG = SRC.read_text(encoding="utf-8")

REG = "            new SetStatusHandler(),\n"
LOOP_TAIL = "      commandTypes.add(handler.type());\n    }\n"
SPARSE = "new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE)"
IMPASSABLE = (
    "new PlanSparseRouteHandler(\n"
    "                new io.mosire.simos.unit.move.MovementCost() {\n"
    "                  @Override\n"
    "                  public java.util.OptionalLong costMillis(\n"
    "                      io.mosire.simos.map.hex.HexCoord from,\n"
    "                      io.mosire.simos.map.hex.HexCoord to,\n"
    "                      io.mosire.simos.unit.Unit unit,\n"
    "                      io.mosire.simos.map.GameMap map) {\n"
    "                    return java.util.OptionalLong.empty();\n"
    "                  }\n"
    "\n"
    "                  @Override\n"
    "                  public long minStepCostMillis(\n"
    "                      io.mosire.simos.unit.Unit unit, io.mosire.simos.map.GameMap map) {\n"
    "                    return 0L;\n"
    "                  }\n"
    "                })"
)


def replace_once(text, needle, repl, label):
    count = text.count(needle)
    if count != 1:
        sys.exit(f"ABORT({label}): 锚点出现 {count} 次（应为 1）: {needle!r}")
    return text.replace(needle, repl)


def main():
    if ORIG.count(REG) != 1:
        sys.exit("ABORT: 注册行锚点不唯一")

    m1 = replace_once(ORIG, REG, "", "m1")
    m1 = replace_once(
        m1,
        "import io.mosire.simos.unit.spi.SetStatusHandler;\n",
        "",
        "m1-import",
    )
    (OUT / "t9m1_Shell.java").write_text(m1, encoding="utf-8")

    m2 = replace_once(ORIG, REG, "", "m2-del")
    m2 = replace_once(
        m2, LOOP_TAIL, LOOP_TAIL + "    coreSimos.register(new SetStatusHandler());\n", "m2-ins"
    )
    (OUT / "t9m2_Shell.java").write_text(m2, encoding="utf-8")

    m3 = replace_once(ORIG, SPARSE, IMPASSABLE, "m3")
    (OUT / "t9m3_Shell.java").write_text(m3, encoding="utf-8")

    for name in ("t9m1_Shell.java", "t9m2_Shell.java", "t9m3_Shell.java"):
        body = (OUT / name).read_text(encoding="utf-8")
        if body == ORIG:
            sys.exit(f"ABORT: {name} 与原件逐字节相同 —— 该变异体作废")
        print(f"wrote {name} ({len(body)} bytes)")


if __name__ == "__main__":
    main()
