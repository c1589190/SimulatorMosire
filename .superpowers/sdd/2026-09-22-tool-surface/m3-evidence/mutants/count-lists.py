#!/usr/bin/env python3
"""从测试源里**数**出各名单常量的条数（现场量，不引用任何文档里的数）。

用法: count-lists.py <SimosToolsTest.java 路径> <常量名> [<常量名> ...]
"""
import re
import sys


def main() -> int:
    if len(sys.argv) < 3:
        print("用法: count-lists.py <文件> <常量名> [...]")
        return 2
    path = sys.argv[1]
    names = sys.argv[2:]
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    if not text.strip():
        print("拒收：读到空文件")
        return 2
    for name in names:
        pattern = re.compile(
            r"private static final List<String> " + re.escape(name) + r"\s*=\s*(.*?);",
            re.DOTALL,
        )
        m = pattern.search(text)
        if not m:
            print("%s: **找不到这个常量**（别把「没搜到」当「是 0 条」）" % name)
            return 2
        body = m.group(1)
        items = re.findall(r'"([^"]*)"', body)
        empty = body.count("List.of()")
        where = "（= List.of()，空名单）" if empty else ""
        print("%s: %d 条%s" % (name, len(items), where))
        if items:
            print("    %s" % ", ".join(items))
    return 0


if __name__ == "__main__":
    sys.exit(main())
