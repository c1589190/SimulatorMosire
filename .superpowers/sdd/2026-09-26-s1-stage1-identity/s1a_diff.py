"""把两份逐格 dump **归一化词表**后逐格逐键比。

判据 I1.1：除了阶层词本身的字面值，**其余每一个字节都必须相同**。

★ 归一化只做一件事：把**标识符位置**上的旧词映射到新词。

  规范串里的阶层是 `ClassKey` 的第二段，即 `…|<slot>`，而紧跟其后的字符只有三种：
  `|`（第三段？不存在，ClassKey 只有两段）、`>`（作债务人）、`-`（作债权人，后接商品名）、
  `"`（串尾）。★ 首版只写了 `[|>]`，漏了 `-` ⇒ `|rich-grain` 没被归一化、报出一堆假差异 —— 已修。

    |peasant  →  |poor_peasant      （ClassKey / DebtId 的规范串）
    |middle   →  |middle_peasant
    |rich     →  |rich_peasant
    "peasant" →  "poor_peasant"     （JSON 字符串值：槽位 id）
    "middle"  →  "middle_peasant"
    "rich"    →  "rich_peasant"

  ★ 两侧边界都收紧，故不会误伤 `poor_peasant`（`|` 后紧跟的是 `poor_`，`peasant` 前是 `_`）。

★ 归一化**只施于基线**那一份（现役那份本来就是新词），然后要求两份**逐字节相等**。
  这样"有没有别的差异"不靠人眼扫，靠 `==` 判。

用法: python3 s1a_diff.py <基线.json> <现役.json>
"""

import json
import re
import sys

# ★ 顺序无关：每条规则都要求边界字符紧邻，故不会互相命中
RULES = [
    (re.compile(r"\|peasant(?=[|>\"-])"), "|poor_peasant"),
    (re.compile(r"\|middle(?=[|>\"-])"), "|middle_peasant"),
    (re.compile(r"\|rich(?=[|>\"-])"), "|rich_peasant"),
    (re.compile(r'"peasant"'), '"poor_peasant"'),
    (re.compile(r'"middle"'), '"middle_peasant"'),
    (re.compile(r'"rich"'), '"rich_peasant"'),
]


def normalize(obj):
    text = json.dumps(obj, ensure_ascii=False, sort_keys=True)
    hits = {}
    for pat, repl in RULES:
        text, n = pat.subn(repl, text)
        hits[pat.pattern] = n
    return json.loads(text), hits


def main():
    base = json.load(open(sys.argv[1]))
    after = json.load(open(sys.argv[2]))

    print(f"tick  基线={base['tick']}  现役={after['tick']}  "
          f"（{'一致 ✓' if base['tick'] == after['tick'] else '★ 不一致 ⇒ 不可比'}）")
    print(f"格数  基线={len(base['hexes'])}  现役={len(after['hexes'])}")

    norm, hits = normalize(base["hexes"])
    print("归一化命中（只施于基线）: " + ", ".join(f"{k}×{v}" for k, v in hits.items() if v))

    only_base = sorted(set(norm) - set(after["hexes"]))
    only_after = sorted(set(after["hexes"]) - set(norm))
    print(f"只出现在基线的格: {only_base[:5]}（{len(only_base)} 个）")
    print(f"只出现在现役的格: {only_after[:5]}（{len(only_after)} 个）")

    diffs = []
    for k in sorted(set(norm) & set(after["hexes"])):
        a = json.dumps(norm[k], ensure_ascii=False, sort_keys=True)
        b = json.dumps(after["hexes"][k], ensure_ascii=False, sort_keys=True)
        if a != b:
            diffs.append((k, a, b))

    print(f"\n★ 逐格差异: {len(diffs)} / {len(norm)} 格")
    for k, a, b in diffs[:5]:
        print(f"\n── 格 {k} ──")
        # 定位第一处不同
        i = next((i for i in range(min(len(a), len(b))) if a[i] != b[i]), min(len(a), len(b)))
        print(f"  基线: …{a[max(0, i - 90):i + 90]}…")
        print(f"  现役: …{b[max(0, i - 90):i + 90]}…")

    if not diffs and not only_base and not only_after:
        print("\n★★ I1.1 成立：归一化词表之后，**逐格逐字节完全相同**（差 0）")
        sys.exit(0)
    sys.exit(1)


if __name__ == "__main__":
    main()
