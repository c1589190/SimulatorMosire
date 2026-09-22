import pathlib, sys

target = pathlib.Path(sys.argv[1])
src = target.read_text(encoding="utf-8")
def mutate(old, new):
    global src
    n = src.count(old)
    assert n == 1, f"锚点命中 {n} 次（必须恰好 1 次）: {old[:80]!r}"
    src = src.replace(old, new)

# m3：轨迹的**结果摘要为空**（"决策人看见了什么"这条载体被抽掉）
mutate("""    invocations.add(
        new ToolInvocation(call.id(), call.name(), result.success(), result.code(), feedback));""",
       """    invocations.add(
        new ToolInvocation(call.id(), call.name(), result.success(), result.code(), ""));""")
target.write_text(src, encoding="utf-8")
