#!/usr/bin/env python3
"""T5 变异体生成器：按 id 对 pristine 文件做定点改动，写出同规范名目标文件。

用法：make-mutant.py <id> <pristine 源> <目标路径>
每处替换断言"恰好命中 N 次"（N 缺省 1），否则非零退出（防静默不生效 ⇒ 假存活）。
"""
import sys
import pathlib

ROUND, SRC, DST = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
text = SRC.read_text(encoding="utf-8")

# 每条 = (old, new, expected_hits)
REPLACEMENTS = {
    # t5m1：列表不筛 affiliation（返回全部）⇒ 两条过滤用例红（判据：过滤后 size 应为 1）。
    "t5m1": [
        (
            "    for (DecisionMaker maker : sd.decisionMakers().values()) {\n"
            "      if (matches(maker.affiliation(), filter)) {\n"
            "        makers.add(maker);\n"
            "      }\n"
            "    }\n",
            "    for (DecisionMaker maker : sd.decisionMakers().values()) {\n"
            "      makers.add(maker);\n"
            "    }\n",
            1,
        )
    ],
    # t5m2：详情缺 cadence 字段 ⇒ 字段断言红（ApiViews.java）。
    "t5m2": [('    view.put("cadence", maker.decisionCadenceTicks());\n', "", 1)],
    # t5m3：空库抛异常（500）⇒ 空列表用例红（判据：空库是 200 空列表，不是错误）。
    "t5m3": [
        (
            "    SdState sd = sdState(target);\n"
            "    List<DecisionMaker> makers = new ArrayList<>();\n",
            "    SdState sd = sdState(target);\n"
            "    if (sd.decisionMakers().isEmpty()) {\n"
            '      throw new IllegalStateException("no decision makers");\n'
            "    }\n"
            "    List<DecisionMaker> makers = new ArrayList<>();\n",
            1,
        )
    ],
    # t5m4：未知 kind 不再抛、改成一个匹配不到任何东西的过滤器 ⇒ 出空列表（fail-closed 被改成"空集合冒充"）⇒ 用例红。
    "t5m4": [
        (
            "      default ->\n"
            '          throw new IllegalArgumentException("未知 affiliation kind（只认 nation / army）: " + kind);\n',
            '      default -> new AffiliationFilter.OfArmy(new ArmyId("__never__"));\n',
            1,
        )
    ],
    # t5m5：列表路由整段去掉 ⇒ /api/sd/decision-makers 落 404 ⇒ 列表用例红（GuiServer.java）。
    "t5m5": [
        (
            "    if (path.equals(DECISION_MAKERS_PATH)) {\n"
            "      return decisionMakersReply(exchange);\n"
            "    }\n",
            "",
            1,
        )
    ],
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
