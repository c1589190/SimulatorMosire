#!/usr/bin/env python3
"""T9 Java 变异体生成器：按 id 对 pristine 文件做定点改动，写出同规范名目标文件。

用法：make-mutant.py <id> <pristine 源> <目标路径>
每处替换断言"恰好命中 N 次"（N 缺省 1），否则非零退出（防静默不生效 ⇒ 假存活）。

★ 编号：
  - t9m1..t9m6：T9 自己的靶子（D7 公式的三分支 / 边界 / max / 字段）。
  - t5m1..t5m5：T5 旧靶子的**重放**——T9 改了 SdQueryService/ApiViews 与既有测试
    （裁定 42：被测文件改动 ⇒ 旧证据作废），故按当前字节重新表达同一语义。
"""
import sys
import pathlib

ROUND, SRC, DST = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
text = SRC.read_text(encoding="utf-8")

# 每条 = (old, new, expected_hits)
REPLACEMENTS = {
    # ── T9：D7 公式的三个分支 ──────────────────────────────────────────────
    # t9m1：due 恒 true（忽略 cadence）⇒ "未到点不 due" 的断言红。
    "t9m1": [("return new PendingSignal(since >= maker.decisionCadenceTicks(), last, since);",
              "return new PendingSignal(true, last, since);", 1)],
    # t9m2："最近一次"取**最小** tick（比较方向反了）⇒ lastDirectiveTick 断言红。
    "t9m2": [("if (last == null || directive.tick() > last) {",
              "if (last == null || directive.tick() < last) {", 1)],
    # t9m3：首次**不** due（无 Directive 也判 false）⇒ "首次恒 due" 的断言红。
    "t9m3": [("return new PendingSignal(true, null, null);",
              "return new PendingSignal(false, null, null);", 1)],
    # t9m4：到点判据写成严格 >（`>= cadence` 改 `> cadence`）⇒ 恰好到点的断言红。
    "t9m4": [("since >= maker.decisionCadenceTicks()",
              "since > maker.decisionCadenceTicks()", 1)],
    # t9m5：暴露层把 due 写死 false ⇒ 所有 due=true 的断言红。
    "t9m5": [('view.put("due", pending.due());',
              'view.put("due", false);', 1)],
    # t9m6：ticksSinceLast 差一（tick−last+1）⇒ 间隔逐值断言红。
    "t9m6": [("long since = tick - last;",
              "long since = tick - last + 1;", 1)],
    # ── T5 重放（当前字节上的同一语义）────────────────────────────────────
    # t5m1：列表不筛 affiliation（返回全部）⇒ 过滤用例红。
    "t5m1": [(
        "    for (DecisionMaker maker : sd.decisionMakers().values()) {\n"
        "      if (matches(maker.affiliation(), filter)) {\n"
        "        makers.add(maker);\n"
        "      }\n"
        "    }\n",
        "    for (DecisionMaker maker : sd.decisionMakers().values()) {\n"
        "      makers.add(maker);\n"
        "    }\n",
        1)],
    # t5m2：详情缺 cadence 字段 ⇒ 字段断言红（ApiViews.java）。
    "t5m2": [('    view.put("cadence", maker.decisionCadenceTicks());\n', "", 1)],
    # t5m3：空库抛异常（500）⇒ 空列表用例红。
    "t5m3": [(
        "    SdState sd = sdState(state);\n"
        "    List<DecisionMaker> makers = new ArrayList<>();\n",
        "    SdState sd = sdState(state);\n"
        "    if (sd.decisionMakers().isEmpty()) {\n"
        '      throw new IllegalStateException("no decision makers");\n'
        "    }\n"
        "    List<DecisionMaker> makers = new ArrayList<>();\n",
        1)],
    # t5m4：未知 kind 不再抛、改成一个匹配不到任何东西的过滤器 ⇒ 空集合冒充 ⇒ 用例红。
    "t5m4": [(
        "      default ->\n"
        '          throw new IllegalArgumentException("未知 affiliation kind（只认 nation / army）: " + kind);\n',
        '      default -> new AffiliationFilter.OfArmy(new ArmyId("__never__"));\n',
        1)],
    # t5m5：列表路由整段去掉 ⇒ 404 ⇒ 列表用例红（GuiServer.java）。
    "t5m5": [(
        "    if (path.equals(DECISION_MAKERS_PATH)) {\n"
        "      rejectAs(path, asPresent);\n"
        "      return decisionMakersReply(params);\n"
        "    }\n",
        "",
        1)],
}

changed = 0
for old, new, expected in REPLACEMENTS[ROUND]:
    hits = text.count(old)
    if hits != expected:
        sys.exit("replacement matched %d times (want %d): %r" % (hits, expected, old[:70]))
    text = text.replace(old, new)
    changed += 1

DST.write_text(text, encoding="utf-8")
print("mutant=%s replacements=%d" % (ROUND, changed))
