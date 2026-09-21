#!/usr/bin/env python3
"""m1（brief §7）：`addGmWrites` 里删掉**一条**工具 ⇒ 期望 `McpPortTopologyTest` 的正向精确匹配红。

★ 删的是**目标类名**（`SimosToolSource.java`）里的一行，不是拿变异体文件顶替它 —— 白名单纪律。
★★ **attempt1 是作废轮（留档不删）**：只删 `built.add(...)` 会把 import 变成 `UnusedImports`
   ⇒ Checkstyle 在 surefire **之前**拦下，rc=1 但 `Tests run` 一行都没有 —— 那是「没跑到」，
   **不是**「没红」。⇒ 变异体必须**连同 import 一起删**，才是一个能跑到断言的形态。
"""
import os
import sys

WT = sys.argv[1]
REL = "simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java"
IMPORT = "import io.mosire.simos.app.tools.write.SdCreateNationTool;\n"
OLD = "    built.add(new SdCreateNationTool(core, initiator, mapId));\n"

path = os.path.join(WT, REL)
with open(path, encoding="utf-8") as handle:
    text = handle.read()
for needle, label in ((IMPORT, "import 行"), (OLD, "built.add 行")):
    hits = text.count(needle)
    if hits != 1:
        raise SystemExit("VOID: 期望 1 处%s，实得 %d 处 —— 基线可能已变" % (label, hits))
    text = text.replace(needle, "", 1)
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text)
print("  edited %s（删掉 sd.CreateNation 的 import 行与 built.add 行）" % REL)
