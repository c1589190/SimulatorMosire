import pathlib, sys

target = pathlib.Path(sys.argv[1])
src = target.read_text(encoding="utf-8")
def mutate(old, new):
    global src
    n = src.count(old)
    assert n == 1, f"锚点命中 {n} 次（必须恰好 1 次）: {old[:80]!r}"
    src = src.replace(old, new)

# m6：提交失败（冲突/被拒）时**照样跑**（触发事实没落盘也烧一次 LLM）
mutate("""    if (!(submitted.result() instanceof CommandResult.Committed committed)) {
      // ★ 触发事实没落盘 ⇒ **不跑**（见类注 第 2 条）：把真实结局（CONFLICT/REJECTED）如实交回去。
      return super.afterSubmit(context, submitted);
    }""",
       """    if (!(submitted.result() instanceof CommandResult.Committed committed)) {
      throw new IllegalStateException("（变异体 m6：忽略提交失败，继续跑）");
    }""")
target.write_text(src, encoding="utf-8")
