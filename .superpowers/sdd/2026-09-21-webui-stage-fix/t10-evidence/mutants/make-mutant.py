#!/usr/bin/env python3
"""T10 Java 变异体生成器：按 id 对 pristine 文件做定点改动，写出同规范名目标文件。

用法：make-mutant.py <id> <pristine 源> <目标路径>
每处替换断言"恰好命中 N 次"（N 缺省 1），否则非零退出（防静默不生效 ⇒ 假存活）。

★ 编号（每个变异体对应一条**被保护断言**）：
  t10m1 GuiServer  用户路径信封的 initiator 改坏        ⇒ C18①（initiator/commandType 断言）红
  t10m9 GuiServer  用户路径不经 core.submit 直接回 200   ⇒ C18①（ref/head）红（"不落 revision"）
  t10m2 窄工具基类  gate() 由 Ask 改 ALLOW               ⇒ C18②（GM 必须先过审批）红
  t10m3 工具源     决策人桶错加 StartDecision            ⇒ C18③ 红
  t10m8 工具源     GM 桶漏加 StartDecision               ⇒ C18②（GM 口工具不存在）红
  t10m4 CatalogTool PAYLOAD_HINTS 漏项                   ⇒ 构造期抛（app 起不来）红
  t10m7 Shell      handler 未注册                        ⇒ C18①（type 未注册 ⇒ 422）红
  t10m5 handler    写一条 Directive（占 R4 名额）        ⇒ "不占 R4" 红
  t10m6 handler    不再拒未知决策人                      ⇒ rejectsUnknownDecisionMaker 红
"""
import sys
import pathlib

ROUND, SRC, DST = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
text = SRC.read_text(encoding="utf-8")

GUI_ENVELOPE_OLD = (
    "            GUI_INITIATOR,\n"
    "            new BranchId(textField(root, \"branch\")),\n"
    "            new RevisionId(longField(root, \"expectedRevision\")),\n"
    "            \"sd.StartDecision\",\n"
)

HANDLER_RETURN_OLD = (
    "      return new HandlerOutcome.Applied(SdChangeSet.between(base, base.withInfo(nextInfo)));"
)

HANDLER_GUARD_OLD = (
    "      if (!base.decisionMakers().containsKey(decisionMakerId)) {\n"
    "        return new HandlerOutcome.Rejected(\"决策人不存在: \" + decisionMakerId.value());\n"
    "      }\n"
    "\n"
)

REPLACEMENTS = {
    # ── ① 用户路径：身份写坏 / 不落 revision ────────────────────────────────
    "t10m1": [
        (
            GUI_ENVELOPE_OLD,
            GUI_ENVELOPE_OLD.replace("            GUI_INITIATOR,\n", "            \"player:local\",\n"),
            1,
        )
    ],
    "t10m9": [
        (
            "    return resultReply(core.submit(command));\n"
            "  }\n"
            "\n"
            "  private static Reply resultReply(CommandResult result) {",
            "    return Reply.of(200, Map.of(\"result\", \"committed\"));\n"
            "  }\n"
            "\n"
            "  private static Reply resultReply(CommandResult result) {",
            1,
        )
    ],
    # ── ② GM Agent 路径：跳过审批 ───────────────────────────────────────────
    "t10m2": [
        (
            "    return new ToolGate.Ask(name(), summary(context.arguments()), AskKind.SENSITIVE);",
            "    return ToolGate.ALLOW;",
            1,
        ),
        ("import io.mosire.agentlib.approval.AskKind;\n", "", 1),
    ],
    # ── ③ 决策人人口：错加工具 ─────────────────────────────────────────────
    "t10m3": [
        (
            "    built.add(new IssueDirectiveTool(core, initiator, mapId));\n"
            "    built.add(new SubmitVerdictTool(core, initiator, mapId));\n"
            "  }\n",
            "    built.add(new IssueDirectiveTool(core, initiator, mapId));\n"
            "    built.add(new SubmitVerdictTool(core, initiator, mapId));\n"
            "    built.add(new StartDecisionTool(core, initiator, mapId));\n"
            "  }\n",
            1,
        )
    ],
    # ── ②' GM 桶漏加工具 ───────────────────────────────────────────────────
    "t10m8": [
        (
            "    built.add(new SetViewScopeTool(core, initiator, mapId));\n"
            "    built.add(new StartDecisionTool(core, initiator, mapId));\n",
            "    built.add(new SetViewScopeTool(core, initiator, mapId));\n",
            1,
        ),
        ("import io.mosire.simos.app.tools.write.StartDecisionTool;\n", "", 1),
    ],
    # ── 载荷提示漏项（构造期抛 ⇒ app 起不来）──────────────────────────────
    "t10m4": [
        (
            ",\n          Map.entry(\"sd.StartDecision\", \"decisionMakerId, note?\"));",
            ");",
            1,
        )
    ],
    # ── handler 未注册 ─────────────────────────────────────────────────────
    "t10m7": [
        (
            "                new CancelEffectHandler(),\n"
            "                new StartDecisionHandler()));",
            "                new CancelEffectHandler()));",
            1,
        ),
        ("import io.mosire.simos.sd.spi.StartDecisionHandler;\n", "", 1),
    ],
    # ── 占 R4 名额：额外写一条 Directive ───────────────────────────────────
    "t10m5": [
        (
            HANDLER_RETURN_OLD,
            "      io.mosire.simos.sd.model.Directive mutantDirective =\n"
            "          new io.mosire.simos.sd.model.Directive(\n"
            "              io.mosire.simos.sd.id.DirectiveId.parse(\"d-mutant\"),\n"
            "              decisionMakerId,\n"
            "              tick,\n"
            "              Optional.empty(),\n"
            "              START_INFO_KEY,\n"
            "              List.of(),\n"
            "              java.util.Set.of(),\n"
            "              Optional.empty(),\n"
            "              io.mosire.simos.sd.model.DirectiveStatus.PLANNED);\n"
            "      java.util.Map<\n"
            "              io.mosire.simos.sd.id.DirectiveId, io.mosire.simos.sd.model.Directive>\n"
            "          nextDirectives = new java.util.LinkedHashMap<>(base.directives());\n"
            "      nextDirectives.put(mutantDirective.id(), mutantDirective);\n"
            "      return new HandlerOutcome.Applied(\n"
            "          SdChangeSet.between(base, base.withInfo(nextInfo).withDirectives(nextDirectives)));",
            1,
        )
    ],
    # ── 不再拒未知决策人 ───────────────────────────────────────────────────
    "t10m6": [(HANDLER_GUARD_OLD, "", 1)],
}

if ROUND not in REPLACEMENTS:
    sys.exit("unknown round: " + ROUND)

changed = 0
for old, new, expected in REPLACEMENTS[ROUND]:
    hits = text.count(old)
    if hits != expected:
        sys.exit("replacement matched %d times (want %d): %r" % (hits, expected, old[:80]))
    text = text.replace(old, new)
    changed += 1

DST.write_text(text, encoding="utf-8")
print("mutant=%s replacements=%d" % (ROUND, changed))
