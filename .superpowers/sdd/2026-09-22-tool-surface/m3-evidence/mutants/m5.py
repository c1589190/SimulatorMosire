#!/usr/bin/env python3
"""m5（★ 不是 brief §7 的必做变异体，是**为 §4.1 的"必须红的地方到底有几处"配的现场测量装置**）。

形态 = **"工具先接线、常量还没跟上"**：只把两份测试文件里的**手抄名单与桶大小**退回 M3 之前的取值，
**生产代码一个字节都不动**（12 个新类仍在、`SimosToolSource.addGmWrites` 仍接着 12 条）。

用途：把"接线之后、改常量之前，哪些断言会自己红"从**回忆**换成**现场量**（brief §3 的"wire first，
run the test, let the assertions red themselves"）。协调方 §4.1 给的是一份**对账用的**清单（brief 写 4 处、
控制器量 6 处）——本装置就是重新量一遍。

★ 本轮的"红"**不是"被杀"**：变异体是个**中间态**、不是缺陷体，所以它的产出是**红点清单**，
不是 KILLED/SURVIVED 的判决。报告里按"红点清单"引用，不得记成"m5 KILLED"。

改动清单（逐处断言唯一命中，命中数不为 1 即 VOID —— 决不允许静默不匹配）：
  SimosToolsTest.java
    · EXTERNAL_UNION_GM_TOOL_NAMES  删掉 12 条 M3 名字（55 → 43）
    · WRITE_TOOL_NAMES              删掉同样 12 条（46 → 34）
    · `.hasSize(52);`（GM 桶）       → `.hasSize(40);`
    · `.hasSize(55);`（现有口）      → `.hasSize(43);`
    · §5.5 扫描守卫 `.hasSize(43)`   → `.hasSize(31)`（连同 .as() 里的数字）
  McpServerTest.java
    · EXTERNAL_UNION_GM_TOOL_NAMES  删掉同样 12 条（55 → 43）—— **第三份手抄名单**
  McpPortTopologyTest.java
    · GM_NARROW_WRITES              删掉 12 条 M3 名字（43 → 31，末行并入 `"),`）

**不**回退的：`SD_WRITE_NAMES`（M3 新增的常量，M3 之前不存在 ⇒ 保持 12）、以及 M3 新增的 §5.2 用例
（接线已落地 ⇒ 它在新常量下是绿的）。这两条正是"新测试自己红自己"与"旧常量跟不上"分界线上的东西。
"""
import hashlib
import os
import re
import sys

WT = sys.argv[1]

SIMOS = "simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java"
MCP_SERVER = "simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java"
PORT = "simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java"

SD_NAMES = [
    "sd.CreateNation",
    "sd.CreateArmy",
    "sd.CreateDecisionMaker",
    "sd.PutInfo",
    "sd.CreateCombat",
    "sd.AddCombatStage",
    "sd.SetStageOutcomeTable",
    "sd.CommitCombatOutcome",
    "sd.RecordCasualties",
    "sd.RegisterEffect",
    "sd.CancelEffect",
    "sd.SetDecisionMakerProvider",
]


def md5(text):
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def read(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def write(path, text):
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(text)


def const_body(text, name):
    """返回该常量**声明语句**在 text 里的 [start, end) 下标（end 指向语句结束的 `);` 之后）。"""
    m = re.search(r"private static final List<String> " + re.escape(name) + r"\s*=\s*", text)
    if not m:
        raise SystemExit("VOID: 找不到常量 %s —— 不敢改（别把「没找到」当「不用改」）" % name)
    try:
        end = text.index(");", m.end()) + 2
    except ValueError:
        raise SystemExit("VOID: 常量 %s 找不到语句结束的 `);`" % name)
    return m.start(), end


def drop_sd_run(slice_text, label):
    """在 slice 里删掉 12 条连块；块在列表末尾时把上一行的逗号并成 `"),`。"""
    lines = slice_text.split("\n")
    first = '"%s",' % SD_NAMES[0]
    hits = []
    for i, line in enumerate(lines):
        if line.strip() != first:
            continue
        ok = True
        for j, name in enumerate(SD_NAMES[1:], start=1):
            if i + j >= len(lines):
                ok = False
                break
            if lines[i + j].strip() not in ('"%s",' % name, '"%s"),' % name):
                ok = False
                break
        if ok:
            hits.append(i)
    if len(hits) != 1:
        raise SystemExit("VOID: %s 里 12 名连块命中 %d 次（期望恰 1 次）—— 世界不干净或文本已变" % (label, len(hits)))
    i = hits[0]
    last = lines[i + 11].strip()
    del lines[i : i + 12]
    merged = None
    if last.endswith('"),'):
        prev = lines[i - 1]
        if not prev.rstrip().endswith(","):
            raise SystemExit("VOID: %s 块尾合并前，上一行不以逗号结尾: %r" % (label, prev))
        # 去掉行尾的逗号，补上 `),` —— 是 `),` 不是 `"),`（忘了那个右引号已经在上一行里，
        # 多补一个引号会造出 `"sd.StartDecision""),` 这种**未闭合字符串字面量**；
        # attempt1 正是这么 VOID 掉的：装置的 COMPILATION ERROR 闸当场拦下，没让它冒充"红"）。
        lines[i - 1] = prev.rstrip()[:-1] + '),'
        merged = lines[i - 1]
    new_text = "\n".join(lines)
    # 结构自证：引号必须成对、且不许出现空串 `""` 或 `"",` 这种错并
    if new_text.count('"') % 2 != 0:
        raise SystemExit("VOID: %s 改完引号个数为奇数 —— 造出了未闭合字面量" % label)
    for bad in ('""', '"",'):
        if bad in new_text:
            raise SystemExit("VOID: %s 改完出现 %r —— 引号并错了" % (label, bad))
    if merged is not None and not merged.strip().endswith('"),'):
        raise SystemExit("VOID: %s 合并行形态不对: %r" % (label, merged))
    return new_text


def drop_from_const(text, const_name):
    start, end = const_body(text, const_name)
    body = text[start:end]
    missing = [n for n in SD_NAMES if ('"%s"' % n) not in body]
    if missing:
        raise SystemExit("VOID: %s 里本来就缺 %s —— 世界不干净" % (const_name, missing))
    new_body = drop_sd_run(body, const_name)
    left = [n for n in SD_NAMES if ('"%s"' % n) in new_body]
    if left:
        raise SystemExit("VOID: %s 删完仍留着 %s" % (const_name, left))
    return text[:start] + new_body + text[end:]


def replace_once(text, old, new, label):
    n = text.count(old)
    if n != 1:
        raise SystemExit("VOID: %s 命中 %d 次（期望恰 1 次）—— 不敢改" % (label, n))
    return text.replace(old, new)


def report(path, before, after):
    print("  %s" % path)
    print("       before md5=%s (%d B)" % (md5(before), len(before.encode("utf-8"))))
    print("       after  md5=%s (%d B)" % (md5(after), len(after.encode("utf-8"))))
    if before == after:
        raise SystemExit("VOID: %s 逐字节没变 —— 这一处等于没变异" % path)


# ── SimosToolsTest.java ────────────────────────────────────────────────
p = os.path.join(WT, SIMOS)
before = read(p)
if not before.strip():
    raise SystemExit("VOID: 读到空文件 %s" % SIMOS)
after = drop_from_const(before, "EXTERNAL_UNION_GM_TOOL_NAMES")
after = drop_from_const(after, "WRITE_TOOL_NAMES")
after = replace_once(after, ".hasSize(52);", ".hasSize(40);", "GM 桶 hasSize(52)")
after = replace_once(after, ".hasSize(55);", ".hasSize(43);", "现有口 hasSize(55)")
after = replace_once(
    after,
    '.as("扫描必须恰为 43 个窄写工具类（扫到 0 个/漏文件是『扫描器静默』陷阱 ⇒ 空 == 空 恒真）").hasSize(43);',
    '.as("扫描必须恰为 31 个窄写工具类（扫到 0 个/漏文件是『扫描器静默』陷阱 ⇒ 空 == 空 恒真）").hasSize(31);',
    "§5.5 扫描守卫 hasSize(43)",
)
# 自证：SD_WRITE_NAMES 必须**原样还在**（12 条，M3 新增的常量不回退）
sd_start, sd_end = const_body(after, "SD_WRITE_NAMES")
sd_body = after[sd_start:sd_end]
kept = [n for n in SD_NAMES if ('"%s"' % n) in sd_body]
if len(kept) != 12:
    raise SystemExit("VOID: SD_WRITE_NAMES 里只剩 %d 条 —— 回退越界了" % len(kept))
print("  [自证] SD_WRITE_NAMES 12 条原样保留（M3 新增常量，不回退）")
report(SIMOS, before, after)
write(p, after)

# ── McpServerTest.java（第三份手抄名单：真 SDK listTools() 对拍的期望集）──
p = os.path.join(WT, MCP_SERVER)
before = read(p)
if not before.strip():
    raise SystemExit("VOID: 读到空文件 %s" % MCP_SERVER)
after = drop_from_const(before, "EXTERNAL_UNION_GM_TOOL_NAMES")
report(MCP_SERVER, before, after)
write(p, after)

# ── McpPortTopologyTest.java ──────────────────────────────────────────
p = os.path.join(WT, PORT)
before = read(p)
if not before.strip():
    raise SystemExit("VOID: 读到空文件 %s" % PORT)
after = drop_from_const(before, "GM_NARROW_WRITES")
# 自证：决策人名单一字未动（M3 不碰 DECISION_AGENT 桶）
if "DECISION_AGENT_WRITES" not in after:
    raise SystemExit("VOID: DECISION_AGENT_WRITES 不见了")
report(PORT, before, after)
write(p, after)

print("  m5 推送完成：工具仍全部接线，只有三份测试文件的**手抄名单与桶大小**退回 M3 之前")
