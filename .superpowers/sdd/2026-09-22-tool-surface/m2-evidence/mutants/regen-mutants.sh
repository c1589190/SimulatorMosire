#!/usr/bin/env bash
# M2 变异体生成器：**从 pristine（干净世界原件）派生**，不手改工作树。
#   产物：whitelist/<mid>/<目标类名>.java —— 文件名 == 目标类名（白名单推送纪律：按变异名拷入会让"红"变成编译错误）。
#   生成后逐个断言：① 与原件**字节不同** ② 与 pristine 的**锚点替换确实命中**（命中数 == 期望，防"静默没改"）。
set -euo pipefail
EV=/home/dev/SimulatorMosire/.claude/worktrees/ts+m1/.superpowers/sdd/2026-09-22-tool-surface/m2-evidence
PRI=$EV/mutants/pristine
WL=$EV/mutants/whitelist
mkdir -p "$WL"

python3 - "$PRI" "$WL" <<'PY'
import sys, pathlib, hashlib

pri, wl = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])

def read(name):
    return pri.joinpath(name).read_text(encoding="utf-8")

def md5(s):
    return hashlib.md5(s.encode("utf-8")).hexdigest()

def emit(mid, target, text, anchors):
    """anchors: [(needle, expected_count)] —— 逐个断言替换命中数，防静默没改。"""
    orig = read(target)
    if text == orig:
        sys.exit(f"★ {mid}/{target}: 变异体与原件逐字节相同 ⇒ 生成失败")
    for needle, want in anchors:
        got = orig.count(needle)
        # 命中数按**被改的那个片段**判，不按整份文件（M1 T5-L5 纪律）
        if got != want:
            sys.exit(f"★ {mid}: 锚点命中 {got} != 期望 {want}: {needle[:60]!r}")
    d = wl / mid
    d.mkdir(parents=True, exist_ok=True)
    d.joinpath(target).write_text(text, encoding="utf-8")
    print(f"  {mid}/{target}  orig_md5={md5(orig)}  mutant_md5={md5(text)}")

# ── m1 决策桶漏一条：把 UnitApplyCasualtiesTool 从 addDecisionAgentWrites 删掉（GM 桶保留）──
#    ★ 两处 add 行逐字相同 ⇒ 先按**只出现一次的注释**切出决策桶那一段，再在段内替换（否则会误删 GM 桶那条）。
src = read("SimosToolSource.java")
mark = "    // M2（spec §八.3）：unit 域 20 条窄写（与 addGmWrites 同一批）。"
assert src.count(mark) == 1, "★ 决策桶注释不唯一 ⇒ 生成失败"
head, sep, tail = src.partition(mark)
line = "    built.add(new UnitApplyCasualtiesTool(core, initiator, mapId));\n"
assert tail.count(line) == 1, "★ 决策桶段内 UnitApplyCasualtiesTool 行数 != 1"
tail_m = tail.replace(line, "", 1)
assert tail_m != tail
m1_src = head + sep + tail_m
assert m1_src.count(line) == 1, "★ GM 桶那一条被误删了（须剩 1）"
emit("m1", "SimosToolSource.java", m1_src, [(line, 2)])

# ── m2 名字错：UnitCancelRouteTool.commandType() 返回另一个**已注册**的 unit 类型 ──
#    ★ 实测：20 个 unit 类型**全部**已有同名窄工具 ⇒ 这个"已注册类型"必然与别的工具**重名**，
#      于是红的是 ToolRegistry 的**重名守卫**（Shell.start 期抛"工具名重复"），**不是**名字同源判据。
#      ⇒ 简报 §7 m2 期望的"名字同源判据红"在这个字面形态下**不可达**（世界比简报假设的更严）。
#      ⇒ 保留 m2 原形（它证明重名守卫生效），另加 **m2b** 把名字同源判据**单独**钉住（无重名的错名）。
canc = read("UnitCancelRouteTool.java")
old = "  @Override\n  protected String commandType() {\n    return NAME;\n  }\n"
assert canc.count(old) == 1, "★ commandType() 锚点不唯一"
new = "  @Override\n  protected String commandType() {\n    return \"unit.PlaceAt\";\n  }\n"
emit("m2", "UnitCancelRouteTool.java", canc.replace(old, new, 1), [(old, 1)])

# ── m2b 名字错（隔离形态）：返回一个**未注册**的错名 ⇒ 无重名 ⇒ 唯有名字同源判据能红 ──
newb = "  @Override\n  protected String commandType() {\n    return \"unit.CancelRouteX\";\n  }\n"
emit("m2b", "UnitCancelRouteTool.java", canc.replace(old, newb, 1), [(old, 1)])

# ── m3 孤儿工具：新增 write/UnitFooTool.java，合法 name 但**不接任何桶** ──
foo = '''package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
 * 孤儿工具（M2 变异体 m3）：实现了窄写工具类，却**没有**接进任何桶。
 *
 * <p>它存在的唯一目的是证明 {@code SimosToolsTest.everyNarrowWriteToolClassIsWiredIntoTheGmBucket}
 * 这条同源判据**不是装饰**——在本变异体出现之前，"写了工具类却忘了接桶"没有任何断言会红。
 */
public final class UnitFooTool extends AbstractNarrowWriteTool {

  public static final String NAME = "unit.NotARealCommand";

  public UnitFooTool(CoreSimos core, String initiator, String mapId) {
    super(core, initiator, mapId);
  }

  @Override
  protected String commandType() {
    return NAME;
  }

  @Override
  protected String summary(Map<String, Object> args) {
    return "孤儿工具 branch=" + args.get("branch");
  }

  @Override
  public String description() {
    return "孤儿工具（变异体）：固定 unit.NotARealCommand——**不接任何桶**，只为证明同源判据有牙";
  }
}
'''
d = wl / "m3"
d.mkdir(parents=True, exist_ok=True)
d.joinpath("UnitFooTool.java").write_text(foo, encoding="utf-8")
print(f"  m3/UnitFooTool.java  （新增文件，无原件）mutant_md5={md5(foo)}")

# ── m4 双侧自证：工具进决策桶 + 手抄常量同步（两处同改）──
#   ★★ 切分键**必须含换行**（m4 首轮实测的坑，形态："把没发生伪装成没发生"）：
#      只按键字符串 partition，插入行会被并进**上一行的 // 注释**里（同一行末尾），
#      于是变异体"合法、可编译、却不生效"——决策桶仍是 31 条，而装置一路全绿报 KILLED。
#      首轮日志 `m4.log` 即此形态（`decisionTool=31` + 那两行被并成一行）。故：
key = mark + "\n"
assert read("SimosToolSource.java").count(key) == 1, "★ 决策桶注释行（含换行）不唯一"
head, sep, tail = read("SimosToolSource.java").partition(key)
assert tail.count("    built.add(new UnitRenameTool(core, initiator, mapId));\n") >= 1
ins = "    built.add(new MapSetTerrainTool(core, initiator, mapId)); // m4：一条 map 窄写也挂进决策人桶\n"
m4_src = head + sep + ins + tail
assert "\n" + ins in m4_src, "★ 插入行不在行首（会被上一行注释吞掉 ⇒ 空操作变异体）"
emit("m4", "SimosToolSource.java", m4_src, [(mark, 1)])

port = read("McpPortTopologyTest.java")
old_line = '      concat(List.of("sd.IssueDirective", "sd.SubmitVerdict"), UNIT_WRITES);'
assert port.count(old_line) == 1, "★ DECISION_AGENT_WRITES 锚点不唯一"
port_m = port.replace(
    old_line,
    '      concat(\n'
    '          List.of("sd.IssueDirective", "sd.SubmitVerdict"), UNIT_WRITES, List.of("map.SetTerrain"));',
    1,
)
emit("m4", "McpPortTopologyTest.java", port_m, [(old_line, 1)])
print("生成完毕。")
PY

echo "--- 白名单目录形态（每个 mid 恰 1~2 个 .java，文件名 == 目标类名）---"
for d in "$WL"/*/; do echo "  $(basename "$d"): $(ls "$d" | tr '\n' ' ')"; done
