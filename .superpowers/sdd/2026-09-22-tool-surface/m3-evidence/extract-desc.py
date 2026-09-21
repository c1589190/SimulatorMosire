"""从简报 §2 的表格逐字抽出 12 条 description() 串（避免手工转录出错）。

用法：python3 extract-desc.py
产物：description-strings.json（自指：含本脚本读到的简报路径与全文 md5）
"""

import hashlib
import json
import re
import sys

BRIEF = ".superpowers/sdd/2026-09-22-tool-surface/task-3-brief.md"


def main():
    raw = open(BRIEF, encoding="utf-8").read()
    rows = []
    for line in raw.splitlines():
        line = line.strip()
        if not line.startswith("| ") or not line.endswith(" |"):
            continue
        # 表格行形如 `| 1 | \`SdXTool\` | \`sd.X\` | \`"…"\` |` ⇒ 去掉首尾竖线后恰 4 格。
        cells = [c.strip() for c in line.strip("|").split("|")]
        if len(cells) != 4:
            continue
        idx, cls, name, desc = cells[0], cells[1], cells[2], cells[3]
        if not re.fullmatch(r"\d+", idx):
            continue
        if not (desc.startswith('`"') and desc.endswith('"`')):
            print("SKIP 形态不符:", idx, repr(desc[:80]))
            continue
        rows.append((int(idx), cls.strip("`"), name.strip("`"), desc[2:-2]))

    out = {
        "_self": {
            "brief_path": BRIEF,
            "brief_sha256": hashlib.sha256(raw.encode("utf-8")).hexdigest(),
            "rows_found": len(rows),
            "indices": [r[0] for r in rows],
        },
        "tools": {},
    }
    for i, cls, name, desc in rows:
        out["tools"][cls] = {"index": i, "name": name, "desc": desc}
        print("--- %2d %s  %s" % (i, cls, name))
        print(repr(desc))

    with open(
        ".superpowers/sdd/2026-09-22-tool-surface/m3-evidence/description-strings.json",
        "w",
        encoding="utf-8",
    ) as handle:
        json.dump(out, handle, ensure_ascii=False, indent=2)

    if len(rows) != 12:
        print("★ 预期 12 行，实得", len(rows), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
