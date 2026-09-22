import pathlib, sys

target = pathlib.Path(sys.argv[1])
src = target.read_text(encoding="utf-8")
def mutate(old, new):
    global src
    n = src.count(old)
    assert n == 1, f"锚点命中 {n} 次（必须恰好 1 次）: {old[:80]!r}"
    src = src.replace(old, new)

# m1：未绑定 provider **静默兜底**（把 fail-closed 换成"随便找个 provider"）
mutate("""    return maker
        .providerId()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    LlmProviderResolver.E_UNBOUND
                        + ": 决策人 "
                        + maker.id().value()
                        + " 未绑定 LLM provider（providerId 为空）——本项绝不落到默认 provider"));""",
       """    return maker.providerId().orElse("stub");""")
target.write_text(src, encoding="utf-8")
