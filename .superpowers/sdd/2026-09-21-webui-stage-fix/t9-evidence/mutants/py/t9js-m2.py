#!/usr/bin/env python3
"""t9js-m2：pendingStatusText 的非布尔分支返回「非待决」（把"没算"伪装成"已判"）。"""
import sys
import pathlib

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = (
    "    if (due === false) {\n"
    '      return "非待决";\n'
    "    }\n"
    '    return "—";\n'
    "  }\n"
)
new = (
    "    if (due === false) {\n"
    '      return "非待决";\n'
    "    }\n"
    '    return "非待决";\n'
    "  }\n"
)
hits = text.count(old)
if hits != 1:
    sys.exit("t9js-m2 anchor matched %d times (want 1)" % hits)
path.write_text(text.replace(old, new), encoding="utf-8")
print("t9js-m2 applied")
