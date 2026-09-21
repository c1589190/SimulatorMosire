"""核对 12 份 Java 里的 description() 字面量与 description-strings.json 逐字节相同。

用法：python3 verify-desc.py
产物：desc-verify.txt（自指：含各文件 md5 与本脚本自身 md5）
"""

import hashlib
import json
import os
import re

EVID = ".superpowers/sdd/2026-09-22-tool-surface/m3-evidence"
DESC_JSON = EVID + "/description-strings.json"
OUT_DIR = "simos-app/src/main/java/io/mosire/simos/app/tools/write"
LOG = EVID + "/desc-verify.txt"


def main():
    payload = json.load(open(DESC_JSON, encoding="utf-8"))
    tools = payload["tools"]
    lines = []
    bad = 0
    for cls, entry in sorted(tools.items(), key=lambda kv: kv[1]["index"]):
        path = os.path.join(OUT_DIR, cls + ".java")
        raw = open(path, "r", encoding="utf-8").read()
        # description() 里的唯一一条 `return "…";`（字面量里无 " 与 \，故非贪婪到行尾的 " 即可）
        match = re.search(r'public String description\(\) \{\n    return "(.*)";\n', raw)
        if not match:
            lines.append("★ %s 找不到 description() 字面量" % cls)
            bad += 1
            continue
        literal = match.group(1)
        ok = literal == entry["desc"]
        name_ok = ('public static final String NAME = "%s";' % entry["name"]) in raw
        file_md5 = hashlib.md5(raw.encode("utf-8")).hexdigest()
        lines.append(
            "%-2d %-32s desc=%s name=%s md5=%s"
            % (
                entry["index"],
                cls,
                "SAME" if ok else "DIFF",
                "SAME" if name_ok else "DIFF",
                file_md5[:12],
            )
        )
        if not ok:
            bad += 1
            lines.append("    json: %r" % entry["desc"])
            lines.append("    java: %r" % literal)
        if not name_ok:
            bad += 1

    self_md5 = hashlib.md5(open(__file__, "rb").read()).hexdigest()
    json_md5 = hashlib.md5(open(DESC_JSON, "rb").read()).hexdigest()
    lines.append("")
    lines.append("本轮跑的是：verify-desc.py md5=%s" % self_md5)
    lines.append("description-strings.json md5=%s" % json_md5)
    lines.append("brief sha256=%s（JSON 自记）" % payload["_self"]["brief_sha256"])
    lines.append("结果：%d 个类，DIFF/缺失 %d 处" % (len(tools), bad))
    text = "\n".join(lines) + "\n"
    print(text, end="")
    with open(LOG, "w", encoding="utf-8") as handle:
        handle.write(text)
    return 1 if bad else 0


if __name__ == "__main__":
    raise SystemExit(main())
