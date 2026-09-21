#!/usr/bin/env python3
"""m2b（m2 的**细粒度**对照轮）：把 `commandType()` 改成**未注册**的类型。

由来：m2 把名字改成**另一个已注册类型** ⇒ 注册表里可能出现重名，启动期就先炸，
那样「红」的理由就不是名字同源判据那一行本身。m2b 换成未注册串，把重名这条干扰因素消掉，
**若 m2b 红在名字同源判据上，而 m2 红在启动期**，两轮一起报即构成完整的杀证据。

★「同源判据本身有没有判别力」这条命题，**只能**由 m2b 这一轮承担（不与 m2 混记）。
"""
import os
import sys

WT = sys.argv[1]
REL = "simos-app/src/main/java/io/mosire/simos/app/tools/write/SdCreateNationTool.java"
OLD = "    return NAME;\n"
NEW = '    return "sd.NotRegisteredType";\n'

path = os.path.join(WT, REL)
with open(path, encoding="utf-8") as handle:
    text = handle.read()
hits = text.count(OLD)
if hits != 1:
    raise SystemExit("VOID: 期望 1 处 `return NAME;`，实得 %d 处" % hits)
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text.replace(OLD, NEW, 1))
print("  edited %s（commandType() 由 sd.CreateNation 改成未注册的 sd.NotRegisteredType）" % REL)
