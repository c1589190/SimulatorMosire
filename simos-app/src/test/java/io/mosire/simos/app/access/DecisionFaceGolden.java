package io.mosire.simos.app.access;

import java.util.Set;

/**
 * ★★ <b>决策人面的测试侧「金名单」</b>（2026-10-12 B4b）—— 决策人**能被谁用**的那批工具名，逐条手写，是**独立规格**。
 *
 * <p>★ <b>为什么它住在测试里、而不是生产里</b>（设计书 §4.4 / 不变量 I-G3）：工具"能被谁用"**只**写在每条工具自己的 {@code
 * SimosTool.roles()} 里，桶与权限组白名单都从它派生。B4 曾在 {@code DecisionCallerFactory} 里留过一份静态 44 条白名单当"启动期校验过的
 * 副本"，B4b 已删 —— 那种副本仍是**第二处声明**：每加一个决策人工具就得改两个地方，忘了改的那次不会报错，只会静默漂移。
 * 规格搬进测试后，漂移的代价变成"当场红"，而生产里**一份清单都没有**。
 *
 * <p>★ <b>本表钉在哪</b>：{@code
 * DecisionCallerFactoryTest.theWhitelistIsExactlyTheReadToolsPlusTheTwoDecisionWrites}
 * 拿它比对**真装配派生出来的**决策人面（{@code ToolBuckets.namesOf(shell.toolsFor(Role.DECISION_AGENT))}）——
 * 往决策人桶加/删任何一条工具而不同步本表 ⇒ 该用例当场红；反过来，改本表而没改 {@code roles()} 声明（或没挂桶）⇒ {@code
 * ToolRoleAudit.requireFaces} 在装配期当场抛。
 *
 * <p>★ <b>它是什么、不是什么</b>：它是**规格**（"决策人面就该是这 44 条"），不是生产常量的副本 —— 没有任何生产代码读它。 别处（{@code
 * GovToolsZ6Test} / {@code LlmToolNamesTest} 等）需要"决策人面的名字"却又不启 {@code Shell} 时也用它， 免得把同一份 44
 * 条抄成好几份。
 *
 * <p>★ <b>口径提醒</b>：表里放的是**工具名**（如 {@code simos.command.catalog}），不是命令类型（如 {@code
 * unit.RenameUnit}）—— 后者是"令里能不能嵌"的另一件事（见 {@code DirectiveWhitelist}）。
 */
public final class DecisionFaceGolden {

  private DecisionFaceGolden() {}

  /** 决策人面的 44 条工具名（2026-10-12 B4b 冻结；与 {@code roles()} 声明、两桶装配三处一致）。 */
  public static final Set<String> ALL =
      Set.of(
          // ── 共享读工具（GM / 决策人两桶共享；标了 GmOnlyRead 的**不在**这里）────────────────
          "simos.command.catalog",
          "simos.state.resolve",
          "simos.state.facets",
          "simos.timeline.branches",
          "simos.timeline.revisions",
          "simos.map.overview",
          "simos.map.hex",
          "simos.map.region",
          "simos.map.render",
          "simos.unit.list",
          "simos.unit.get",
          "simos.social.population",
          "simos.social.households",
          // ★ C7a：历法读口是四桶共享读（未标 GmOnlyRead）。
          "simos.calendar.info",
          // ★ 工具面补齐 / T1c：交战记录与 army 读口属"世界状态·四桶共享"。
          "simos.sd.combats",
          "simos.army.combats",
          "simos.army.combat",
          // ★ R2a：逐格经济读数（视野由工具内收窄，不越界）。
          "simos.economy.hex",
          // ★★ D5：两条外交读口（世界级自然语言、四桶共享读）。
          "simos.sd.diplomacy",
          "simos.sd.diplomatic-events",
          // ★ D4：跨区上报读口（决策人与 GM 共用）。
          "simos.sd.reports",
          // ★★ Z3c-2：GOV 行政运行只读读口（决策人视野收窄到自己 GOV）。
          "simos.gov.info",
          // ── 决策人专属读口与决策方法论 ────────────────────────────────────────────────
          // ★ 第 10 条：决策人查看**自己**的决策结果（"决策 → 看结果 → 再决策"的回路）。
          "sd.DecisionResults",
          // ★ 第 11 条：Docs 系统——发给自己的设定文档（"这一局我该怎么做"）。
          "sd.DecisionDocs",
          // ★ 第 12 条：Skill 系统——方法论与常识（两桶共享，读外部 Markdown）。
          "simos.skill",
          // ── 决策行为（两条，语义就靠它们）────────────────────────────────────────────
          "ga.IssueDirective",
          "ga.SubmitVerdict",
          // ── 受限窄写（身份派生 + 审批链；进了白名单也**不放大**权限）──────────────────────
          // ★★ D5（D-003 / D-004 / D-005 / R5 / R6）：外交边 / 外交事件 / 向本 GOV 付款。
          "gov.SetDiplomaticRelation",
          "gov.RecordDiplomaticEvent",
          "simos.gov.pay",
          // ★★ A1 / A2a：货币身份三件套 + 官方汇率（只能作用于自己的 GOV）。
          "simos.gov.defineCurrency",
          "simos.gov.renameCurrency",
          "simos.gov.issueMoney",
          "simos.gov.setFxRate",
          // ★★ Z3c-2 / Z3d：五条政府配置窄写 + 外部岗位开放。
          "simos.gov.setEstablishment",
          "simos.gov.setBudgetPolicy",
          "simos.gov.assignPosts",
          "simos.gov.expandHousehold",
          "simos.gov.openPostsToMarket",
          // ★★ D2（2026-10-22 决策包计划）：决策包四件套。
          "simos.sd.propose",
          "simos.sd.packet.submit",
          "simos.sd.packet.intent",
          "simos.sd.packet.my",
          // ★★ D4：跨区上报写口（发送人 = 调用者身份）。
          "simos.sd.report");
}
