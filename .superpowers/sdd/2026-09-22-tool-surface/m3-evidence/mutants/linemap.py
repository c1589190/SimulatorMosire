#!/usr/bin/env python3
"""把**变异体文件**里的红点行号，映射回**基线（干净）文件**里的行号 —— 用**原文精确匹配**量出来，
不靠行数加减法推导（本仓纪律：能被推导出来的都不算"验过"）。

用法: linemap.py <变异体文件> <变异体行号>[,<行号>...] <基线文件>
输出: 变异体行号 → 该行原文 → 基线文件里**恰含该原文**的行号（一行为空或匹配 0/多行都要报错）
"""
import sys


def main() -> int:
    if len(sys.argv) != 4:
        print("用法: linemap.py <变异体文件> <行号[,行号...]> <基线文件>")
        return 2
    mut_path, lines_arg, base_path = sys.argv[1], sys.argv[2], sys.argv[3]
    with open(mut_path, encoding="utf-8") as handle:
        mut_lines = handle.read().split("\n")
    with open(base_path, encoding="utf-8") as handle:
        base_lines = handle.read().split("\n")
    if not mut_lines or not base_lines:
        print("拒收：读到空文件")
        return 2
    for token in lines_arg.split(","):
        n = int(token)
        if n < 1 or n > len(mut_lines):
            print("拒收：行号 %d 越界（变异体共 %d 行）" % (n, len(mut_lines)))
            return 2
        text = mut_lines[n - 1]
        stripped = text.strip()
        if not stripped:
            print("拒收：变异体第 %d 行是空行 —— 空行匹配不出任何东西" % n)
            return 2
        hits = [i + 1 for i, line in enumerate(base_lines) if line.strip() == stripped]
        if len(hits) != 1:
            print("变异体 :%-5d → 基线里命中 %d 行（期望恰 1）—— 不敢给行号" % (n, len(hits)))
            print("    原文: %s" % stripped[:120])
            continue
        print("变异体 :%-5d → 基线 :%-5d   %s" % (n, hits[0], stripped[:100]))
    return 0


if __name__ == "__main__":
    sys.exit(main())
