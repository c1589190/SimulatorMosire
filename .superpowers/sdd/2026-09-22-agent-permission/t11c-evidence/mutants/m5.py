import pathlib, sys

target = pathlib.Path(sys.argv[1])
src = target.read_text(encoding="utf-8")
def mutate(old, new):
    global src
    n = src.count(old)
    assert n == 1, f"锚点命中 {n} 次（必须恰好 1 次）: {old[:80]!r}"
    src = src.replace(old, new)

# m5：触发工具**没接进任何桶**（孤儿工具：写好了却谁也不认识它）。
# ★ 首轮把 import 留下了 ⇒ Checkstyle 先拦（UnusedImports）⇒ 0 个报告 ⇒ 装置判 VOID（不是 SURVIVED）。
#   这一版连 import 一起去掉，才真的走到"工具不在桶里"这个状态。
mutate("""    // T11C：触发**决策人自己**跑一轮（真 LLM + 真工具）。★ **只在 GM 桶**——决策人不触发自己（那是自环）。
    built.add(new RunDecisionTool(core, initiator, mapId, decisionAgent));
""", "")
mutate("import io.mosire.simos.app.tools.write.RunDecisionTool;\n", "")
target.write_text(src, encoding="utf-8")
