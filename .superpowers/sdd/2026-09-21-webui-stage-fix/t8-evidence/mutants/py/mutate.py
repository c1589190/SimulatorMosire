#!/usr/bin/env python3
"""T8 变异体生成器：按 id 对 pristine 文件做定点改动（每处断言"恰好命中 N 次"）。

用法：mutate.py <round-id> <目标文件>
round-id 决定 (期望 basename, 替换列表)。T8 新靶子 = t8m*；`r*` = 裁定 42 的**前序变异轮重放**
（T4 的 Shell 靶、T6/T5 的 GuiServer 靶、T7 的 WebAssets 靶、T1/T7 的前端靶）。
"""
import pathlib
import sys

ROUND = sys.argv[1]
TARGET = pathlib.Path(sys.argv[2])

R = {
    # ── T8 新靶子 ────────────────────────────────────────────────────────
    # t8m1：记录装饰器不再记 execute ⇒ 服务端记录为空 ⇒ Java 端到端断言红。
    "t8m1": ("RecordingToolSource.java", [
        (
            "      ToolResult result = delegate.execute(context);\n"
            "      usage.record(name(), result.success(), result.code(), System.currentTimeMillis());\n"
            "      return result;\n",
            "      return delegate.execute(context);\n",
            1,
        )
    ]),
    # t8m8：端点不读记录、恒空壳 ⇒ "工具真的被用过却看不到" 的 Java 断言红。
    "t8m8": ("GuiServer.java", [
        (
            "    for (GmToolUsage.Entry entry : gmToolUsage.recent()) {\n",
            "    for (GmToolUsage.Entry entry : List.<GmToolUsage.Entry>of()) {\n",
            1,
        )
    ]),
    # t8m2：gm.js 丢弃服务端 entries（硬编码空壳）⇒ 渲染"来自服务端记录"的断言红。
    "t8m2": ("gm.js", [
        (
            "    if (body && Array.isArray(body.entries)) {\n      return body.entries;\n    }\n",
            "    if (body && Array.isArray(body.entries)) {\n      return [];\n    }\n",
            1,
        )
    ]),
    # t8m3：底栏按钮不接 panel（点了没反应）⇒ mount 接线断言红。
    "t8m3": ("gm.js", [
        (
            '    button.addEventListener("click", function () {\n'
            "      setOpen(overlay, button, true);\n"
            "      refreshNow();\n"
            "    });\n",
            "",
            1,
        )
    ]),
    # t8m4：全屏层 position 改 absolute ⇒ 参与文档流、可能挡住/挤出底栏 ⇒ 静态断言红。
    "t8m4": ("styles.css", [
        (
            "body.workbench-page .gm-overlay {\n  position: fixed;",
            "body.workbench-page .gm-overlay {\n  position: absolute;",
            1,
        )
    ]),
    # t8m5：全屏层 pointer-events 改 none ⇒ 在 .wb-overlay 内点不到 ⇒ 静态断言红。
    "t8m5": ("styles.css", [
        (
            "  color: var(--text);\n  pointer-events: auto;\n}\n\nbody.workbench-page .gm-overlay[hidden] {",
            "  color: var(--text);\n  pointer-events: none;\n}\n\nbody.workbench-page .gm-overlay[hidden] {",
            1,
        )
    ]),
    # t8m6：全屏层丢掉默认 hidden ⇒ 一进来就盖住底栏 ⇒ 静态断言红。
    "t8m6": ("index.html", [
        (
            '<section id="gm-overlay" class="gm-overlay" hidden role="dialog"',
            '<section id="gm-overlay" class="gm-overlay" role="dialog"',
            1,
        )
    ]),
    # ── 裁定 42：T4 的 Shell 靶重放 ─────────────────────────────────────
    "r4m1": ("Shell.java", [
        ("SimosToolSource.Role.EXTERNAL_WITH_GM);", "SimosToolSource.Role.EXTERNAL);", 1)
    ]),
    "r4m2": ("Shell.java", [
        ("SimosToolSource.Role.DECISION_AGENT);", "SimosToolSource.Role.EXTERNAL);", 1)
    ]),
    "r4m3": ("Shell.java", [
        (
            "    guiServer.close();\n    mcpServer.close();\n    decisionMcpServer.close();\n    toolBridge.close();\n",
            "    guiServer.close();\n    mcpServer.close();\n    toolBridge.close();\n",
            1,
        )
    ]),
    "r4t7m1": ("Shell.java", [
        ("              toolAuthorizer);\n", "              ToolCallAuthorizer.standard());\n", 2)
    ]),
    "r4t7m2": ("Shell.java", [
        (
            "        AccessToken.DEFAULT,\n        AgentPermissionSet.unrestricted(AccessToken.DEFAULT),\n",
            "        AccessToken.GUEST,\n        AgentPermissionSet.unrestricted(AccessToken.GUEST),\n",
            1,
        )
    ]),
    "r4t11m1": ("Shell.java", [
        (
            "    guiServer.close();\n    mcpServer.close();\n    decisionMcpServer.close();\n",
            "    guiServer.close();\n    decisionMcpServer.close();\n",
            1,
        )
    ]),
    "r4t9m1": ("Shell.java", [
        ("            new SetStatusHandler(),\n", "", 1),
        ("import io.mosire.simos.unit.spi.SetStatusHandler;\n", "", 1),
    ]),
    "r4t9m2": ("Shell.java", [
        ("            new SetStatusHandler(),\n", "", 1),
        (
            "      commandTypes.add(handler.type());\n    }\n",
            "      commandTypes.add(handler.type());\n    }\n    coreSimos.register(new SetStatusHandler());\n",
            1,
        ),
    ]),
    "r4t9m3": ("Shell.java", [
        (
            "new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE)",
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
            "                })",
            1,
        )
    ]),
    # ── 裁定 42：T6/T5 的 GuiServer 靶重放 ──────────────────────────────
    "r6m3": ("GuiServer.java", [
        (
            '    if (asPresent) {\n'
            '      throw new IllegalArgumentException(\n'
            '          "读端点 " + path + " 未接入 data redaction，不支持 as= 视角参数（fail-closed）");\n'
            "    }\n",
            "",
            1,
        )
    ]),
    "r6m5": ("GuiServer.java", [
        (
            "    if (asPresent && !redactingQueryService.seesHex(actor, target, coord)) {\n"
            '      // fail-closed：不可见的格与"不存在"同形（都不给存在性侧信道）。\n'
            "      Map<String, Object> body = ApiViews.hexCoord(coord);\n"
            '      body.put("error", "hex not found");\n'
            "      return Reply.of(404, body);\n"
            "    }\n",
            "",
            1,
        )
    ]),
    "r6t5m5": ("GuiServer.java", [
        (
            "    if (path.equals(DECISION_MAKERS_PATH)) {\n"
            "      rejectAs(path, asPresent);\n"
            "      return decisionMakersReply(params);\n"
            "    }\n",
            "",
            1,
        )
    ]),
    # ── 裁定 42：T7 的 index.html 靶（Java 护栏）＋ T1/T7 前端靶 ─────────
    "r7m11": ("index.html", [
        (
            'data-mode="decision" aria-pressed="false">决策</button>',
            'data-mode="decision" aria-pressed="false">Decide</button>',
            1,
        )
    ]),
    # r1m1（裁定 42 重派生）：T1 原靶锚点已被 T7 重写 ⇒ 按当前字节重派生同一语义
    #   （把右栏审批计数加回 index.html），静态断言应红。
    "r1m1": ("index.html", [
        (
            '    <div id="notifications" class="notify-bar" hidden aria-live="polite" aria-label="待批审批"></div>\n',
            '    <div id="notifications" class="notify-bar" hidden aria-live="polite" aria-label="待批审批"></div>\n'
            '    <p id="approvals-count" class="status muted">审批未接入（T6）</p>\n',
            1,
        )
    ]),
    "r7m3": ("index.html", [
        (
            '        <button type="button" class="mode" data-mode="decision" aria-pressed="false">决策</button>\n',
            " ",
            1,
        )
    ]),
}

if ROUND not in R:
    sys.exit("unknown round: " + ROUND)

expected_base, replacements = R[ROUND]
if TARGET.name != expected_base:
    sys.exit("round %s targets %s, got %s" % (ROUND, expected_base, TARGET.name))

text = TARGET.read_text(encoding="utf-8")
for old, new, expected in replacements:
    hits = text.count(old)
    if hits != expected:
        sys.exit("round %s replacement matched %d times (want %d): %r" % (ROUND, hits, expected, old[:70]))
    text = text.replace(old, new)
TARGET.write_text(text, encoding="utf-8")
print("mutant=%s replacements=%d" % (ROUND, len(replacements)))
