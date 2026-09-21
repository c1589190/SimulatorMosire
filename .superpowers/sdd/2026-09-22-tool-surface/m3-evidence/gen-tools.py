"""生成 12 个窄写工具类（description() 串由 description-strings.json 逐字取自简报，不经手抄）。

用法：python3 gen-tools.py
产物：simos-app/src/main/java/io/mosire/simos/app/tools/write/Sd*Tool.java ×12
"""

import json
import os

OUT_DIR = "simos-app/src/main/java/io/mosire/simos/app/tools/write"
DESC_JSON = ".superpowers/sdd/2026-09-22-tool-surface/m3-evidence/description-strings.json"

# (类名, NAME, summary 的中文动作短语, 类 Javadoc 正文行)
TOOLS = [
    (
        "SdCreateNationTool",
        "sd.CreateNation",
        "建国家",
        [
            "{@code sd.CreateNation} 窄工具（M3，spec §八.3）：**建国家**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**（故 {@code EXTERNAL_WITH_GM} 复合口也含它）：命令类型固定，模型只能给载荷。标",
            "sensitive ⇒ 走审批门链。",
            "",
            "<p>★ **{@code homeRegionId} 指向的区域必须带 {@code nation:} 前缀的 tag**（R13）——这是建国家的前置，"
            "缺它即被域层拒绝，理由原文到达调用方（工具层不重复校验：那份校验能被 {@code simos.command.submit} "
            "绕过 ⇒ 是装饰）。",
        ],
    ),
    (
        "SdCreateArmyTool",
        "sd.CreateArmy",
        "建军",
        [
            "{@code sd.CreateArmy} 窄工具（M3，spec §八.3）：**建军**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★ 三条前置（id 已存在 / {@code nationId} 不存在 / {@code rootUnitId} 不存在）都在**域层**判、逐条有可读文案"
            "——工具层不重复校验（同上）。",
        ],
    ),
    (
        "SdCreateDecisionMakerTool",
        "sd.CreateDecisionMaker",
        "建决策人",
        [
            "{@code sd.CreateDecisionMaker} 窄工具（M3，spec §八.3）：**建决策人**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★ {@code allowedTools} 含通用写（{@code simos.command.submit}）即被拒（N9）；★ 创建期 {@code viewScope}",
            "恒为空范围、本命令**不接受**该字段——传了会被**静默忽略**（无拒绝）。配权走 {@code sd.SetViewScope}。",
        ],
    ),
    (
        "SdPutInfoTool",
        "sd.PutInfo",
        "写 Info",
        [
            "{@code sd.PutInfo} 窄工具（M3，spec §六/§八.3）：**写 sd 侧 INFO** 的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★ 写的是**感知层**（供 UI / AAR 展示），ground truth 仍由领域模块持有；地址非法 / {@code key} 空白 /",
            "{@code value} 缺失都在命令期被拒、理由原文到达调用方。",
        ],
    ),
    (
        "SdCreateCombatTool",
        "sd.CreateCombat",
        "建交战",
        [
            "{@code sd.CreateCombat} 窄工具（M3，spec §八.3）：**建交战**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★ 只建交战本身（阶段表为空）：首个 {@code sd.AddCombatStage} 才建对应的战斗状态。",
            "{@code participants} 缺省即空集 ⇒ **可建出零参与者交战**。",
        ],
    ),
    (
        "SdAddCombatStageTool",
        "sd.AddCombatStage",
        "加战斗阶段",
        [
            "{@code sd.AddCombatStage} 窄工具（M3，spec §八.3）：**给交战加阶段**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★★ **{@code combatStateId} / {@code hex} 只在该交战的首个阶段生效**：非首阶段时给了会被**静默忽略**",
            "——不报错、回执也看不出它有没有生效。这是**工具面上唯一的披露点**（见 {@link #description()}）。",
        ],
    ),
    (
        "SdSetStageOutcomeTableTool",
        "sd.SetStageOutcomeTable",
        "设阶段结局表",
        [
            "{@code sd.SetStageOutcomeTable} 窄工具（M3，spec §八.3）：**设某阶段的结局表**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★ 结局表是**整体替换**；空表或权重 ≤0 被域层拒（N2），理由原文到达调用方。",
        ],
    ),
    (
        "SdCommitCombatOutcomeTool",
        "sd.CommitCombatOutcome",
        "定结局",
        [
            "{@code sd.CommitCombatOutcome} 窄工具（M3，spec §八.3）：**给交战选定结局**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★ 结局必须落在**该阶段**的结局表里（只在别的阶段的表里也拒）；且**已选定过就不再覆盖**（N2 恰一个）。",
        ],
    ),
    (
        "SdRecordCasualtiesTool",
        "sd.RecordCasualties",
        "记战损",
        [
            "{@code sd.RecordCasualties} 窄工具（M3，spec §八.3）：**记战损**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★ 上界由代码判（N3，绝不交给 AI）：{@code |Δ| <= 当前值}，越界**命令期拒绝**；**未知装备键被拒、不视作",
            "0**（拼错键不会被静默忽略成「没这条装备」）。{@code deltas[].personnel} / {@code equipment} 缺省分别取 0 /",
            "空表 ⇒ **可写出零损失记录**。",
        ],
    ),
    (
        "SdRegisterEffectTool",
        "sd.RegisterEffect",
        "登记效果",
        [
            "{@code sd.RegisterEffect} 窄工具（M3，spec §八.3）：**登记效果（ECA 规则）**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★ {@code trigger} / {@code action} 引用的实体**在命令期解析**，解析不到即拒（含 {@code action} 引用的命令",
            "不在白名单）；{@code createdTick} 缺省 = **当前 tick**，回执区分不出「我给的」与「引擎填的」。",
        ],
    ),
    (
        "SdCancelEffectTool",
        "sd.CancelEffect",
        "取消效果",
        [
            "{@code sd.CancelEffect} 窄工具（M3，spec §八.3）：**取消效果**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**：命令类型固定，模型只能给载荷。标 sensitive ⇒ 走审批门链。",
            "",
            "<p>★ 只有**可取消状态**（{@code PLANNED} / {@code COMMITTED}）的效果才允许；已触发 / 已取消 / 已过期的被域层拒。",
        ],
    ),
    (
        "SdSetDecisionMakerProviderTool",
        "sd.SetDecisionMakerProvider",
        "配 provider",
        [
            "{@code sd.SetDecisionMakerProvider} 窄工具（M3）：**给决策人绑定 LLM provider 引用**的唯一窄写面。",
            "",
            "<p>★ **只在 GM 桶**（故 {@code EXTERNAL_WITH_GM} 复合口也含它）：命令类型固定，模型只能给载荷。标",
            "sensitive ⇒ 走审批门链。",
            "",
            "<p>★★ **本命令只校验 {@code providerId} 非空白，不校验 provider 是否存在**：存在性由**使用时刻**的解析强制，",
            "解析不到即 fail-closed，**绝不静默兜底**。⇒ 工具层**有意不加**存在性校验：那份校验能被",
            "{@code simos.command.submit} 绕过（＝装饰），而「从工具面看不出写错了」这件事已写进 {@link #description()}。",
        ],
    ),
]

TEMPLATE = """package io.mosire.simos.app.tools.write;

import io.mosire.simos.core.CoreSimos;
import java.util.Map;

/**
{javadoc}
 */
public final class {cls} extends AbstractNarrowWriteTool {{

  public static final String NAME = "{name}";

  public {cls}(CoreSimos core, String initiator, String mapId) {{
    super(core, initiator, mapId);
  }}

  @Override
  protected String commandType() {{
    return NAME;
  }}

  @Override
  protected String summary(Map<String, Object> args) {{
    return "{phrase} branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }}

  @Override
  public String description() {{
    return {desc};
  }}
}}
"""


def main():
    payload = json.load(open(DESC_JSON, encoding="utf-8"))
    tools = payload["tools"]
    assert len(tools) == 12, len(tools)
    written = []
    for cls, name, phrase, javadoc_lines in TOOLS:
        entry = tools[cls]
        assert entry["name"] == name, (cls, entry["name"], name)
        desc = entry["desc"]
        assert '"' not in desc and "\\" not in desc, "串里出现了需转义的字符，生成器要跟上"
        javadoc = "\n".join(
            (" * " + line) if line else " *" for line in javadoc_lines
        )
        text = TEMPLATE.format(
            javadoc=javadoc, cls=cls, name=name, phrase=phrase, desc='"' + desc + '"'
        )
        path = os.path.join(OUT_DIR, cls + ".java")
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(text)
        written.append((path, len(text)))
    for path, size in written:
        print("写出 %s (%d B)" % (path, size))
    print("共 %d 个类；description 串来自 %s" % (len(written), DESC_JSON))


if __name__ == "__main__":
    main()
