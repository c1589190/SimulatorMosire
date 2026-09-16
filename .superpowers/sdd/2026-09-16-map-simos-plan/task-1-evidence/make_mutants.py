#!/usr/bin/env python3
# 生成 R1 修复轮的 7 个变异体（每个 = 原件副本 + 恰好一处定向替换）。
# 每条 needle 都断言"命中次数 == 1"，避免替换到别处或替换了 0 处却静默通过。
import pathlib
import sys

REF = pathlib.Path("/tmp/hexmut-r1/ref-r1")
OUT = pathlib.Path("/tmp/hexmut-r1/mutants")

MUTANTS = [
    (
        "R-M1.HexCoord.java",
        "HexCoord.java",
        """    int i = text.indexOf('_');
    if (i <= 0 || i == text.length() - 1) {
      throw new IllegalArgumentException("非法坐标串: " + text);
    }
""",
        """    int i = text.indexOf('_');
""",
    ),
    (
        "R-M2.HexCoord.java",
        "HexCoord.java",
        "return HexDirection.ALL.stream().map(this::neighbor).toList();",
        "return HexDirection.ALL.reversed().stream().map(this::neighbor).toList();",
    ),
    (
        "R-M3.HexDirection.java",
        "HexDirection.java",
        "return ALL.get((ordinal() + 1) % 6);",
        "return ALL.get((ordinal() + 5) % 6);",
    ),
    (
        "R-M4.HexGrid.java",
        "HexGrid.java",
        """    if (radius < 0) {
      throw new IllegalArgumentException("半径不能为负: " + radius);
    }
""",
        "",
    ),
    (
        "R-M5.HexCoord.java",
        "HexCoord.java",
        """    double s = -q - r;
    int rq = (int) Math.round(q);
    int rr = (int) Math.round(r);
    int rs = (int) Math.round(s);
    double dq = Math.abs(rq - q);
    double dr = Math.abs(rr - r);
    double ds = Math.abs(rs - s);
    if (dq > dr && dq > ds) {
      rq = -rr - rs;
    } else if (dr > ds) {
      rr = -rq - rs;
    }
    // 其余情形（s 轴偏差最大或并列）**故意不写 else**：该分支要修正的是 s 轴，而 s 是导出的、不参与返回，
    // 于是 `rs = -rq - rr;` 对返回值毫无影响——SpotBugs 会当场判它 DLS_DEAD_LOCAL_STORE。若在此改动 q/r 反而错。
    return new HexCoord(rq, rr);
""",
        "    return new HexCoord((int) Math.round(q), (int) Math.round(r));\n",
    ),
    (
        "R-M6.HexCoord.java",
        "HexCoord.java",
        """    if (!Double.isFinite(q) || !Double.isFinite(r)) {
      throw new IllegalArgumentException("坐标必须有限: " + q + ", " + r);
    }
""",
        "",
    ),
    (
        "R-M7.HexCoord.java",
        "HexCoord.java",
        "return (Math.abs(q - other.q) + Math.abs(r - other.r) + Math.abs(s() - other.s())) / 2;",
        "return (Math.abs(q - other.q) + Math.abs(r - other.r)) / 2;",
    ),
]

OUT.mkdir(parents=True, exist_ok=True)
bad = 0
for name, src, needle, repl in MUTANTS:
    text = (REF / src).read_text(encoding="utf-8")
    n = text.count(needle)
    if n != 1:
        print(f"!! {name}: needle 命中 {n} 次（要求恰好 1 次）—— 作废", file=sys.stderr)
        bad += 1
        continue
    (OUT / name).write_text(text.replace(needle, repl), encoding="utf-8")
    print(f"{name}: needle 命中 1 次，已写出")
# R-M3 的第二处（prev 的 +5 -> +1）：上面那处改完后 next 已是 +5，
# 故这里对**改过的文本**再换一次，保证是"真交换"而不是"两处同值"。
m3 = OUT / "R-M3.HexDirection.java"
text = m3.read_text(encoding="utf-8")
needle = """  public HexDirection prev() {
    return ALL.get((ordinal() + 5) % 6);
  }"""
repl = """  public HexDirection prev() {
    return ALL.get((ordinal() + 1) % 6);
  }"""
n = text.count(needle)
if n != 1:
    print(f"!! R-M3 第二处: 命中 {n} 次 —— 作废", file=sys.stderr)
    bad += 1
else:
    m3.write_text(text.replace(needle, repl), encoding="utf-8")
    print("R-M3 第二处（prev 的 +5 -> +1）已写出——两处是真交换")

sys.exit(1 if bad else 0)
