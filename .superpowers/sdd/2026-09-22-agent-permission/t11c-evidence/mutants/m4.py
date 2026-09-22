import pathlib, sys

target = pathlib.Path(sys.argv[1])
src = target.read_text(encoding="utf-8")
def mutate(old, new):
    global src
    n = src.count(old)
    assert n == 1, f"锚点命中 {n} 次（必须恰好 1 次）: {old[:80]!r}"
    src = src.replace(old, new)

# m4：把触发工具放进**决策人白名单**（决策人本不该有"触发一轮"的权限）
mutate("""          IssueDirectiveTool.NAME,
          SubmitVerdictTool.NAME);""",
       """          IssueDirectiveTool.NAME,
          SubmitVerdictTool.NAME,
          RunDecisionTool.NAME);""")
mutate('import io.mosire.simos.app.tools.write.IssueDirectiveTool;',
       'import io.mosire.simos.app.tools.write.IssueDirectiveTool;\nimport io.mosire.simos.app.tools.write.RunDecisionTool;')
target.write_text(src, encoding="utf-8")
