#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""证明 `spotless:apply` 那一步是**注释/折行**级改动、**零语义改动**。

做法：把两份 Java 源码里的注释全部剥掉（字符串感知：`"…"` 与字符字面量里的 `//`、`/*` 不当注释），
再去掉所有空白字符，然后**逐字节比对剩余文本**。相等 ⇒ 除注释与空格外一字未动。

★ 为什么需要它：变异轮的基线 md5 锚定的是**格式化前**的字节；若不做这一步，"轮次证据"与"最终提交字节"
之间就有一处无法解释的差。这一纸证明把那处差钉成"只有注释/折行"。

用法：strip-compare.py <pre-spotless.java> <current.java>
"""
import sys


def strip(path: str) -> str:
    text = open(path, encoding="utf-8").read()
    out = []
    i, n = 0, len(text)
    while i < n:
        c = text[i]
        if c == '"' or c == "'":
            quote = c
            out.append(c)
            i += 1
            while i < n:
                if text[i] == "\\":
                    out.append(text[i : i + 2])
                    i += 2
                    continue
                out.append(text[i])
                if text[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        if text.startswith("//", i):
            while i < n and text[i] != "\n":
                i += 1
            continue
        if text.startswith("/*", i):
            end = text.find("*/", i + 2)
            i = n if end < 0 else end + 2
            continue
        out.append(c)
        i += 1
    return "".join(out)


def main() -> int:
    a, b = sys.argv[1], sys.argv[2]
    sa, sb = strip(a), strip(b)
    squeeze = lambda s: "".join(s.split())
    if squeeze(sa) == squeeze(sb):
        print(f"IDENTICAL(代码面)  {a}  ==  {b}   [注释/空格外零改动]")
        return 0
    print(f"DIFFERENT(代码面)  {a}  !=  {b}")
    ta, tb = squeeze(sa), squeeze(sb)
    for k in range(min(len(ta), len(tb))):
        if ta[k] != tb[k]:
            print(f"  首个差异 @{k}: …{ta[max(0,k-60):k+60]!r}  vs  …{tb[max(0,k-60):k+60]!r}")
            break
    else:
        print(f"  前缀相同，长度不同: {len(ta)} vs {len(tb)}")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
