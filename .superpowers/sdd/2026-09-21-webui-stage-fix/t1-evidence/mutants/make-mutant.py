#!/usr/bin/env python3
"""T1 变异体生成器：按 id 对 pristine 文件做一处定点改动，写出同规范名目标文件。

用法：make-mutant.py <m1|m2|m3> <pristine 源> <目标路径>
每处替换都断言"恰好命中一次"，否则非零退出（防静默不生效 ⇒ 假存活）。
"""
import sys
import pathlib

ROUND, SRC, DST = sys.argv[1], pathlib.Path(sys.argv[2]), pathlib.Path(sys.argv[3])
text = SRC.read_text(encoding="utf-8")

REPLACEMENTS = {
    # m1：把右栏审批块加回 index.html（静态断言应红）
    "m1": [
        (
            '          <div id="region-panel-mount"></div>\n        </div>\n      </section>',
            '          <div id="region-panel-mount"></div>\n        </div>\n'
            '        <h3>待批</h3>\n'
            '        <p id="approvals-count" class="status muted">审批未接入（T6）</p>\n'
            '      </section>',
        )
    ],
    # m2：通知栏不再读 /api/approvals（动态 fetch 记录断言应红）
    "m2": [("        return api.approvals();", "        return Promise.resolve({ pending: [] });")],
    # m3：删 api.approvals 但不改 write-allowlist.test.cjs（该文件应红 —— 隐藏断点）
    "m3": [
        ('  function approvals() {\n    return getJson("/approvals");\n  }\n\n', ""),
        ("    approvals: approvals,\n", ""),
    ],
}

changed = 0
for old, new in REPLACEMENTS[ROUND]:
    hits = text.count(old)
    if hits != 1:
        sys.exit("replacement matched %d times (want 1): %r" % (hits, old[:60]))
    text = text.replace(old, new)
    changed += 1

DST.write_text(text, encoding="utf-8")
print("mutant=%s replacements=%d" % (ROUND, changed))
