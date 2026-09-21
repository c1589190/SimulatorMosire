#!/usr/bin/env python3
"""t9js-m1：列表项的待决文本写死「待决」（不再读服务端 maker.due）。"""
import sys
import pathlib

path = pathlib.Path(sys.argv[1])
text = path.read_text(encoding="utf-8")
old = "text: pendingStatusText(maker.due)"
new = 'text: "待决"'
hits = text.count(old)
if hits != 1:
    sys.exit("t9js-m1 anchor matched %d times (want 1)" % hits)
path.write_text(text.replace(old, new), encoding="utf-8")
print("t9js-m1 applied")
