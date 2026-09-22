import pathlib, sys

target = pathlib.Path(sys.argv[1])
src = target.read_text(encoding="utf-8")
def mutate(old, new):
    global src
    n = src.count(old)
    assert n == 1, f"锚点命中 {n} 次（必须恰好 1 次）: {old[:80]!r}"
    src = src.replace(old, new)

# m2：工具结果**不含轨迹**（toolCalls 恒为空）
mutate('    view.put("toolCalls", toolCallsView(turn.toolInvocations()));',
       '    view.put("toolCalls", List.of());')
target.write_text(src, encoding="utf-8")
