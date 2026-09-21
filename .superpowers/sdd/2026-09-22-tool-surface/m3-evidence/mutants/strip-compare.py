#!/usr/bin/env python3
"""证「spotless:apply 只动了注释」：把两份 Java 源里的注释与空白全部剥掉后逐字节比对。

为什么要独立写一份而不复用 T8 的同名脚本：T8 的脚本在**另一棵树的证据目录**里，
跨任务引用别人的装置等于拿"我记得它这么判"当依据（形态 5）。这里自带、可复核。

剥法（状态机，**尊重字符串/字符字面量**）：
  · 双引号串与单引号字符里的 `//`、`/*` **不是注释**，原样保留（只把转义序列按两字符跳过）；
  · `//` 到行尾、`/* */`（含 `/** */` Javadoc）整体丢弃；
  · 所有空白折叠成单个空格、行首尾空白去掉；
  · 不属于注释/空白的**每一个字节**都参与比对。
⇒ 输出 IDENTICAL 表示「两边的非注释字节序列逐字节相同」。

用法: strip-compare.py <A.java> <B.java>
"""
import sys


def strip_comments(text: str):
    """返回 (code, literals)：code 是**字面量之外**的字节（注释丢弃、空白折叠），
    literals 是**所有字符串/字符字面量的原样字节**（空白**不**折叠）。

    ★ 为什么要分开：若只做一次全局空白折叠，**字符串字面量里的空格被改动**也会判「相同」——
      那就把「只动注释」证过头了。分开之后，字面量里改一个字节都会被两侧比对抓住。
    """
    code_parts = []
    buf = []
    lits = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            i += 2
            while i < n and text[i] != "\n":
                i += 1
        elif ch == "/" and nxt == "*":
            i += 2
            while i < n and not (text[i] == "*" and i + 1 < n and text[i + 1] == "/"):
                i += 1
            i += 2
        elif ch == '"' or ch == "'":
            # 字面量之前先把普通段收进 code_parts
            code_parts.append(" ".join("".join(buf).split()))
            buf = []
            quote = ch
            lit = [ch]
            i += 1
            while i < n and text[i] != quote:
                if text[i] == "\\" and i + 1 < n:
                    lit.append(text[i])
                    lit.append(text[i + 1])
                    i += 2
                    continue
                lit.append(text[i])
                i += 1
            if i < n:
                lit.append(text[i])
                i += 1
            lits.append("".join(lit))
        else:
            buf.append(ch)
            i += 1
    code_parts.append(" ".join("".join(buf).split()))
    return " ".join(part for part in code_parts if part), "".join(lits)


def digest(text: str) -> str:
    import hashlib

    return hashlib.md5(text.encode("utf-8")).hexdigest()


def main() -> int:
    if len(sys.argv) != 3:
        print("用法: strip-compare.py <A.java> <B.java>")
        return 2
    left, right = sys.argv[1], sys.argv[2]
    with open(left, encoding="utf-8") as handle:
        a = handle.read()
    with open(right, encoding="utf-8") as handle:
        b = handle.read()
    # ★ 先断言读到非空 —— 「读到空」不能当「相等」（形态 5/6 的教训）
    if not a.strip() or not b.strip():
        print("拒收：读到空文件（A=%d 字节 / B=%d 字节）" % (len(a), len(b)))
        return 2
    # 双侧自证：① 两份原文的**原文** md5 必须不同（否则等于没比）；
    #          ② 剥注释后的字节数必须量出来（不许只报结论）
    import hashlib

    raw_a = hashlib.md5(a.encode("utf-8")).hexdigest()
    raw_b = hashlib.md5(b.encode("utf-8")).hexdigest()
    ca, la = strip_comments(a)
    cb, lb = strip_comments(b)
    print("A 原文 md5 = %s (%d 字节)" % (raw_a, len(a)))
    print("B 原文 md5 = %s (%d 字节)" % (raw_b, len(b)))
    print("A 剥注释后 code = %d 字节, md5=%s" % (len(ca), digest(ca)))
    print("B 剥注释后 code = %d 字节, md5=%s" % (len(cb), digest(cb)))
    print("A 字面量合计     = %d 字节, md5=%s" % (len(la), digest(la)))
    print("B 字面量合计     = %d 字节, md5=%s" % (len(lb), digest(lb)))
    if raw_a == raw_b:
        print("拒收：两份原文逐字节相同 —— 这个比对证明不了任何事")
        return 2
    if ca == cb and la == lb:
        print("VERDICT: IDENTICAL（非注释 code 与**全部字符串/字符字面量**都逐字节相同 ⇒ 只动了注释/空白）")
        return 0
    if la != lb:
        print("VERDICT: DIFFERENT（**字符串/字符字面量被改动了**）")
        for idx in range(min(len(la), len(lb))):
            if la[idx] != lb[idx]:
                print("  首个分歧在字面量第 %d 字节：A=%r B=%r" % (idx, la[idx], lb[idx]))
                print("  A 上下文: %s" % la[max(0, idx - 40) : idx + 40])
                print("  B 上下文: %s" % lb[max(0, idx - 40) : idx + 40])
                break
        return 1
    print("VERDICT: DIFFERENT（**字面量之外的代码被改动了**）")
    for idx in range(min(len(ca), len(cb))):
        if ca[idx] != cb[idx]:
            print("  首个分歧在 code 第 %d 字节：A=%r B=%r" % (idx, ca[idx], cb[idx]))
            print("  A 上下文: %s" % ca[max(0, idx - 60) : idx + 60])
            print("  B 上下文: %s" % cb[max(0, idx - 60) : idx + 60])
            break
    return 1


if __name__ == "__main__":
    sys.exit(main())
