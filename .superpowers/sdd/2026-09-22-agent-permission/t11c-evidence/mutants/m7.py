import pathlib, sys

target = pathlib.Path(sys.argv[1])
src = target.read_text(encoding="utf-8")
def mutate(old, new):
    global src
    n = src.count(old)
    assert n == 1, f"锚点命中 {n} 次（必须恰好 1 次）: {old[:80]!r}"
    src = src.replace(old, new)

# m7：撞上回合预算**不按类型区分**（折成普通 IllegalStateException ⇒ 调用方只能看到一句含糊的失败，
#     报不出"是否因预算中止"）。
mutate("""          e.llmCalls());
      throw e;""",
       """          e.llmCalls());
      throw new IllegalStateException(e.getMessage());""")
target.write_text(src, encoding="utf-8")
