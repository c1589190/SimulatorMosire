#!/usr/bin/env python3
"""m2（brief §7）：把一条 sd 窄写的 `commandType()` 改成**另一个已注册的 sd 类型** ⇒ 期望名字同源判据红。

★ 改的是 `SdCreateNationTool.commandType()` 的返回串本身（目标类名里的字节），不是换文件。
"""
import os
import sys

WT = sys.argv[1]
REL = "simos-app/src/main/java/io/mosire/simos/app/tools/write/SdCreateNationTool.java"
OLD = "    return NAME;\n"
NEW = '    return "sd.CreateArmy";\n'

path = os.path.join(WT, REL)
with open(path, encoding="utf-8") as handle:
    text = handle.read()
hits = text.count(OLD)
if hits != 1:
    raise SystemExit("VOID: 期望 1 处 `return NAME;`，实得 %d 处" % hits)
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text.replace(OLD, NEW, 1))
print("  edited %s（commandType() 由 sd.CreateNation 改成已注册的 sd.CreateArmy）" % REL)
