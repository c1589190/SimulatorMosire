#!/usr/bin/env python3
"""T4 变异体生成器：按 id 对 pristine 文件做定点改动，写出同规范名目标文件。

用法：make-mutant.py <id> <pristine 源> <目标路径>
每处替换断言"恰好命中 N 次"（N 缺省 1），否则非零退出（防静默不生效 ⇒ 假存活）。

★ 裁定 42：`t4r-*` 是**既有变异轮在当前字节上的重放**（旧快照含 T3 前的 import，不得整套套用）：
  t4r-t7m1 / t4r-t7m2 来自 M5 T7 m1/m2；t4r-t11m1 来自 M5 T11 m1；t4r-m10m2 来自 M10 m2。
  t9m1/m2/m3 由 `make-mutants-t9.py` 从当前 Shell.java 重新派生（同目录）。
"""
import sys
import pathlib

ROUND, SRC, DST = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
text = SRC.read_text(encoding="utf-8")

# 每条 = (old, new, expected_hits)
REPLACEMENTS = {
    # ── T4 新靶子（Shell.java）───────────────────────────────────────────────
    # t4m1：现有口退回 EXTERNAL（丢掉 GM 三条窄工具）⇒ C6 的逐条集合断言红。
    "t4m1": [("SimosToolSource.Role.EXTERNAL_WITH_GM);", "SimosToolSource.Role.EXTERNAL);", 1)],
    # t4m2：决策人口错挂 EXTERNAL 桶（含通用写）⇒ C7 与 C9 红。
    "t4m2": [("SimosToolSource.Role.DECISION_AGENT);", "SimosToolSource.Role.EXTERNAL);", 1)],
    # t4m3：close() 漏关第二个 server ⇒ C8（关闭后决策人口仍可连）红。
    "t4m3": [
        (
            "    guiServer.close();\n"
            "    mcpServer.close();\n"
            "    decisionMcpServer.close();\n"
            "    toolBridge.close();\n",
            "    guiServer.close();\n"
            "    mcpServer.close();\n"
            "    toolBridge.close();\n",
            1,
        )
    ],
    # t4m4：决策人口错加 sd.SetViewScope ⇒ C7 的"不含 SetViewScope"红（SimosToolSource.java）。
    "t4m4": [
        (
            "    built.add(new IssueDirectiveTool(core, initiator, mapId));\n"
            "    built.add(new SubmitVerdictTool(core, initiator, mapId));\n"
            "  }\n",
            "    built.add(new IssueDirectiveTool(core, initiator, mapId));\n"
            "    built.add(new SubmitVerdictTool(core, initiator, mapId));\n"
            "    built.add(new SetViewScopeTool(core, initiator, mapId));\n"
            "  }\n",
            1,
        )
    ],
    # ── 裁定 42 重放（Shell.java）───────────────────────────────────────────
    # t4r-t7m1（M5 T7 m1）：两个口的 authorizer 都换成不带审批的 standard() ⇒ 写不再进审批。
    "t4r-t7m1": [("              toolAuthorizer);\n", "              ToolCallAuthorizer.standard());\n", 2)],
    # t4r-t7m2（M5 T7 m2）：mcpCaller 桶 DEFAULT → GUEST ⇒ 写被硬拒、不进审批。
    "t4r-t7m2": [
        (
            "        AccessToken.DEFAULT,\n"
            "        AgentPermissionSet.unrestricted(AccessToken.DEFAULT),\n",
            "        AccessToken.GUEST,\n"
            "        AgentPermissionSet.unrestricted(AccessToken.GUEST),\n",
            1,
        )
    ],
    # t4r-t11m1（M5 T11 m1）：close() 漏关第一个 server ⇒ 现有口关闭后仍可连。
    "t4r-t11m1": [
        (
            "    guiServer.close();\n"
            "    mcpServer.close();\n"
            "    decisionMcpServer.close();\n",
            "    guiServer.close();\n"
            "    decisionMcpServer.close();\n",
            1,
        )
    ],
    # t4r-m10m2（M10 m2）：缺省绑定地址 127.0.0.1 → 0.0.0.0（ShellConfig.java）。
    "t4r-m10m2": [
        (
            '  public static final String DEFAULT_BIND_ADDRESS = "127.0.0.1";',
            '  public static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";',
            1,
        )
    ],
}

changed = 0
for old, new, expected in REPLACEMENTS[ROUND]:
    hits = text.count(old)
    if hits != expected:
        sys.exit(
            "replacement matched %d times (want %d): %r" % (hits, expected, old[:70])
        )
    text = text.replace(old, new)
    changed += 1

DST.write_text(text, encoding="utf-8")
print("mutant=%s replacements=%d" % (ROUND, changed))
