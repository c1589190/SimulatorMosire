import pathlib, sys

target = pathlib.Path(sys.argv[1])
src = target.read_text(encoding="utf-8")
def mutate(old, new):
    global src
    n = src.count(old)
    assert n == 1, f"锚点命中 {n} 次（必须恰好 1 次）: {old[:80]!r}"
    src = src.replace(old, new)

# m9：catalog 的**载荷提示缺项**（登记了新命令类型却没补提示）
mutate('          Map.entry("sd.RunDecision", "decisionMakerId"),\n', '')
target.write_text(src, encoding="utf-8")
