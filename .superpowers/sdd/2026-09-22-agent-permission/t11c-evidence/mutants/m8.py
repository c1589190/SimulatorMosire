import pathlib, sys

target = pathlib.Path(sys.argv[1])
src = target.read_text(encoding="utf-8")
def mutate(old, new):
    global src
    n = src.count(old)
    assert n == 1, f"锚点命中 {n} 次（必须恰好 1 次）: {old[:80]!r}"
    src = src.replace(old, new)

# m8：触发事实的 key 与 StartDecision **混同**（两条事实在 AAR 里长得一样）
mutate('public static final String RUN_INFO_KEY = "run";',
       'public static final String RUN_INFO_KEY = "start";')
target.write_text(src, encoding="utf-8")
