#!/usr/bin/env python3
"""M3 评审发现 2 的修复：去掉工具面注释里**钉死的条数**（brief §6(e)①）。

★ 每一处都先断言**恰好命中 1 次**再替换 —— 命中数不对就整脚本作废、不落盘。
   （本仓形态：「空 == 空 恒真」与「基线已变」都靠这个守卫当场暴露。）
★ 本脚本**只动注释**：8 处 old/new 逐条都是 javadoc / `//` 行，无一条是语句。
   证明见同目录 strip-compare（实现者一份 + 控制器一份，各自独立写）。
"""
import os
import sys

WT = sys.argv[1]
SS = "simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java"
SH = "simos-app/src/main/java/io/mosire/simos/app/Shell.java"

EDITS = [
    # ---- SimosToolSource.java ----
    (SS,
     " * Simos 工具供给源（M5 T5，spec §7.1）：**9 读 + 各桶自己的写面**（{@link Role}）。",
     " * Simos 工具供给源（M5 T5，spec §7.1）：**读工具各桶共享 + 各桶自己的写面**（{@link Role}）。"),
    (SS,
     '     * 现有 MCP 口（T4，**D2="加"**，spec §二.5）：**EXTERNAL ∪ GM** —— 9 读共享 + 通用写（submit/advance/fork）+ GM',
     '     * 现有 MCP 口（T4，**D2="加"**，spec §二.5）：**EXTERNAL ∪ GM** —— 读工具共享 + 通用写（submit/advance/fork）+ GM'),
    (SS,
     "   * 外部 MCP 桶（与既有行为逐条相同：3 写 + 9 读）。",
     "   * 外部 MCP 桶（与既有行为逐条相同：通用写 + 读工具）。"),
    (SS,
     "   * （**本注刻意不写条数**：数字是漂移源，M2 已把本文件与 {@code Shell} 的旧计数去掉）。",
     "   * （**本注刻意不写条数**：数字是漂移源；M3 起本文件与 {@code Shell} 的工具面注释一律不钉条数）。"),
    (SS,
     "    // M2（spec §八.3）：unit 域 20 条窄写 —— 用户裁定 D-1，同一批**也挂进决策人桶**（见 addDecisionAgentWrites）。",
     "    // M2（spec §八.3）：unit 域窄写 —— 用户裁定 D-1，同一批**也挂进决策人桶**（见 addDecisionAgentWrites）。"),
    (SS,
     "   * 决策 Agent 窄写（N9）：**无** `sd.SetViewScope`、**无**通用写。★ 用户裁定 D-1：unit 域 20 条窄写与 GM",
     "   * 决策 Agent 窄写（N9）：**无** `sd.SetViewScope`、**无**通用写。★ 用户裁定 D-1：unit 域窄写与 GM"),
    (SS,
     "    // M2（spec §八.3）：unit 域 20 条窄写（与 addGmWrites 同一批）。",
     "    // M2（spec §八.3）：unit 域窄写（与 addGmWrites 同一批）。"),
    # ---- Shell.java ----
    (SH,
     "    // 工具集（T5/T4）：现有口 = EXTERNAL ∪ GM（**D2=\"加\"**，spec §二.5）= 9 读 + 通用写 + GM 窄写（条数以工具面为准）。",
     "    // 工具集（T5/T4）：现有口 = EXTERNAL ∪ GM（**D2=\"加\"**，spec §二.5）= 读工具 + 通用写 + GM 窄写（条数以工具面为准）。"),
    (SH,
     "    // 决策人口（T4，spec §二.2/§六.4）：仅 DECISION_AGENT 桶（9 读 + 该桶自有窄写），**无**通用写、**无** sd.SetViewScope。",
     "    // 决策人口（T4，spec §二.2/§六.4）：仅 DECISION_AGENT 桶（读工具 + 该桶自有窄写），**无**通用写、**无** sd.SetViewScope。"),
]

# 读入 + 预检（全部命中数正确才落盘：先算后写，不留半改状态）
texts = {}
for rel, old, new in EDITS:
    if rel not in texts:
        with open(os.path.join(WT, rel), encoding="utf-8") as handle:
            texts[rel] = handle.read()
    hits = texts[rel].count(old)
    if hits != 1:
        raise SystemExit("VOID: %s 期望 1 处命中，实得 %d 处 —— 未落盘" % (rel, hits))
    if texts[rel].count(new) != 0:
        raise SystemExit("VOID: %s 的新文本已存在 —— 脚本可能被重复执行" % rel)
    texts[rel] = texts[rel].replace(old, new, 1)

# 全部预检通过后才落盘（不留半改状态）
for rel in texts:
    with open(os.path.join(WT, rel), "w", encoding="utf-8") as handle:
        handle.write(texts[rel])
    print("  edited %s" % rel)
print("  共 %d 处注释改动（期望 9）" % len(EDITS))
