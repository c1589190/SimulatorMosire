#!/usr/bin/env python3
"""T10 前端变异体生成器：按 id 对 pristine 文件做定点改动。

用法：make-js-mutant.py <id> <pristine 源> <目标路径>
每处替换断言"恰好命中 N 次"，否则非零退出（防静默不生效 ⇒ 假存活）。

  t10js-m1 panels.js：闸门恒 enabled（忽略 due）        ⇒ start-decision-gate-requires-due 红
  t10js-m2 panels.js：发起动作丢掉闸门守卫             ⇒ start-decision-refuses-when-target-is-not-due 红
  t10js-m3 api.js   ：窄端点写成别的路径               ⇒ api.js-declares-exactly-the-allowed-write-endpoints 红
  t10js-m4 panels.js：按钮不再绑定发起动作             ⇒ panels-js-and-app-js-delegate-subpage-visibility 红
"""
import sys
import pathlib

ROUND, SRC, DST = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
text = SRC.read_text(encoding="utf-8")

REPLACEMENTS = {
    "t10js-m1": [("    if (maker.due === true) {\n", "    if (true) {\n", 1)],
    "t10js-m2": [
        (
            "    if (!target || !startDecisionGate(target).enabled) {\n",
            "    if (!target) {\n",
            1,
        )
    ],
    "t10js-m3": [
        (
            'return postJson("/sd/start-decision", body);',
            'return postJson("/sd/start-decision-x", body);',
            1,
        )
    ],
    "t10js-m4": [
        (
            '      startButton.addEventListener("click", decideStartDecision);\n',
            "      void startButton;\n",
            1,
        )
    ],
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
