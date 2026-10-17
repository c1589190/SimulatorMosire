package io.mosire.simos.app.access;

import io.mosire.docs.DocsTools;
import io.mosire.workspace.WorkspaceTools;
import java.util.Set;

/**
 * ★★ <b>决策人面的测试侧「金名单」</b>（2026-10-12 B4b）—— 决策人**能被谁用**的那批工具名，逐条手写，是**独立规格**。
 *
 * <p>★ <b>为什么它住在测试里、而不是生产里</b>（设计书 §4.4 / 不变量 I-G3）：工具"能被谁用"**只**写在每条工具自己的 {@code
 * SimosTool.toolRoles()} 里，桶与权限组白名单都从它派生。B4 曾在 {@code DecisionCallerFactory} 里留过一份静态 44
 * 条白名单当"启动期校验过的 副本"，B4b 已删 —— 那种副本仍是**第二处声明**：每加一个决策人工具就得改两个地方，忘了改的那次不会报错，只会静默漂移。
 * 规格搬进测试后，漂移的代价变成"当场红"，而生产里**一份清单都没有**。
 *
 * <p>★ <b>本表钉在哪</b>：{@code
 * DecisionCallerFactoryTest.theWhitelistIsExactlyTheReadToolsPlusTheTwoDecisionWrites}
 * 拿它比对**真装配派生出来的**决策人面（{@code ToolBuckets.namesOf(shell.toolsFor(Role.DECISION_AGENT))}）——
 * 往决策人桶加/删任何一条工具而不同步本表 ⇒ 该用例当场红；反过来，改本表而没改 {@code toolRoles()} 声明（或没挂桶）⇒ {@code
 * ToolRoleAudit.requireFaces} 在装配期当场抛。
 *
 * <p>★ <b>它是什么、不是什么</b>：它是**规格**（"决策人面就该是这 54 条"），不是生产常量的副本 —— 没有任何生产代码读它。 别处（{@code
 * GovToolsZ6Test} / {@code LlmToolNamesTest} 等）需要"决策人面的名字"却又不启 {@code Shell} 时也用它， 免得把同一份 44
 * 条抄成好几份。
 *
 * <p>★ <b>口径提醒</b>：表里放的是**工具名**（如 {@code simos.command.catalog}），不是命令类型（如 {@code
 * unit.RenameUnit}）—— 后者是"令里能不能嵌"的另一件事（见 {@code DirectiveWhitelist}）。
 */
public final class DecisionFaceGolden {

  private DecisionFaceGolden() {}

  /**
   * 决策人面的 61 条工具名（2026-10-12 B4b 冻结 44 条；★★ 2026-10-13 收尾测试第 3 层删掉幽灵 {@code simos.sd.combats} ⇒
   * 43；★★ 2026-10-13 取缔 Skill 系统删掉 Skill 读工具一条 ⇒ 42；★★ 2026-10-14 AgentLib Docs/Workspace 接入
   * +6（doc_list/read/query/append/create/edit）⇒ 48；★★ <b>2026-10-14 决策人互致消息</b>：旧上报族整族废弃 ⇒ 删 {@code
   * simos.sd.report}（写口）与 {@code simos.sd.reports}（读口）、加 {@code simos.ga.message.send} 与 {@code
   * simos.ga.message.inbox} ⇒ 那一步仍是 48（−2 +2，条数逐字不变但**成员换了**）；★★ <b>2026-10-14 上游 R8</b> （控制方裁定
   * §11.3）：{@code doc_guide} / {@code workspace_info} 两条"能力自述"工具都是纯读 ⇒ {@code {GM, DM}} ⇒ 48 →
   * <b>50</b>；★★ <b>2026-10-14 决策自由化（批 B）</b>：四件套改名 sd → ga（**改名不计增**）+ 净新增 revise / drop / reopen
   * 与 adjudication.ack ⇒ <b>50 → 54</b>；★★ <b>2026-10-15 用户裁定「甲」（批 K ①）</b>：7 条漏打 DM 标签的类1 补 {@code
   * roles={DM,GM}} ⇒ <b>54 → 61</b>。与 {@code toolRoles()} 声明、两桶装配三处一致。
   *
   * <p>★ <b>50 → 54 的算法（实测，不是照抄设计书）</b>：设计书 §7 只预测"DM 桶 50→51"，真跑读到的是 <b>54</b>。 逐条核过：决策族 4 → 8
   * 条（改名 3 条不计增，净 +4：propose 之外新增 revise / drop / reopen 三条 + adjudication.ack 一条），其余 46 条一字未动 ⇒
   * 54。★ 三条 GM 侧新工具（{@code gm.inbox} / {@code gm.message.send} / {@code gm.round}）{@code
   * toolRoles()} 只有 GM ⇒ 一条都不进本表。
   *
   * <p>★★ <b>54 → 61 的算法（实测，不是照抄设计书）</b>：用户 2026-10-15 裁定「甲」——{@code RaiseUnit} · {@code
   * LevyRegion} · {@code GovRecruit} · {@code GovRetireStaff} · {@code SocialHouseholdMembers} ·
   * {@code GovSelectExaminees} · {@code GovDispatchTeam} 这 7 条「当年的 DM 标签是**漏打的**」（它们本来就是决策人的核心动作：
   * 造兵 / 征税 / 招吏 / 辞退 / 选人 / 派队 / 家户成员直调）⇒ 补 {@code roles={DM,GM}}。 ★ 逐条**是**真跑读数（{@code
   * DecisionCallerFactoryTest} 的失败输出里那 7 条 actual-only 成员），不是从设计书抄的清单； ★ 它们 {@code gate()}
   * <b>一个字未改</b> ⇒ 自动成类2 {@code DM_APPROVAL}（「DM/GM 混合就是要交给 GM 审核」），而不是类3。★ 与 {@code
   * ToolClassAuditAssemblyTest.DM_APPROVAL_NAMES}（同样含这 7 条）同轮同步 —— 那一边按**装配分区**读，本表按**工具面**读，两处独立。
   */
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
          // ★★ 2026-10-13 收尾测试第 3 层：**删掉幽灵条目 `simos.sd.combats`** —— 旧战斗族退役后
          //   交战读口只剩 `simos.army.combats` / `simos.army.combat`（main 里对 `simos.sd.combats` 零命中）。
          //   它一条幽灵压住 DecisionCallerFactoryTest 的整个用例（DM 面 43 = 金名单 44 − 1 幽灵）。
          "simos.army.combats",
          "simos.army.combat",
          // ★ R2a：逐格经济读数（视野由工具内收窄，不越界）。
          "simos.economy.hex",
          // ★★ D5：两条外交读口（世界级自然语言、四桶共享读）。
          "simos.ga.diplomacy",
          "simos.ga.diplomatic-events",
          // ★★ D4 的跨区上报读口 simos.sd.reports 与写口 simos.sd.report **已随旧上报族整族废弃**
          //   （2026-10-14 决策人互致消息，用户原话「老的直接扔掉」）⇒ 这里**删掉那两条**。
          // ★★ 2026-10-14 决策人互致消息（设计书 §4.2/§4.3）：决策人面新增两条消息工具 ——
          //   发（写感知层，闸门 = GM 事先授权 peer:<对方>，gate=ALLOW）与收（读寄给自己的）。
          "simos.ga.message.send",
          "simos.ga.message.inbox",
          // ★★ Z3c-2：GOV 行政运行只读读口（决策人视野收窄到自己 GOV）。
          "simos.gov.info",
          // ── 决策人专属读口与决策方法论 ────────────────────────────────────────────────
          // ★ 第 10 条：决策人查看**自己**的决策结果（"决策 → 看结果 → 再决策"的回路）。
          "simos.ga.decisionResults",
          // ★ 第 11 条：Docs 系统——发给自己的设定文档（"这一局我该怎么做"）。
          "simos.ga.decisionDocs",
          // ── 决策行为（两条，语义就靠它们）────────────────────────────────────────────
          "ga.IssueDirective",
          "ga.SubmitVerdict",
          // ── 受限窄写（身份派生 + 审批链；进了白名单也**不放大**权限）──────────────────────
          // ★★ D5（D-003 / D-004 / D-005 / R5 / R6）：外交边 / 外交事件 / 向本 GOV 付款。
          "simos.gov.setDiplomaticRelation",
          "simos.gov.recordDiplomaticEvent",
          "simos.gov.pay",
          // ★★ 2026-10-15 用户裁定「甲」（批 K ①）：这 7 条**原本就在本表里**（它们是决策人的核心动作，D2
          //   决策包计划第一天就在可提议清单里），只是当年的 toolRoles() **漏打了 DM 标签** ⇒ 本轮补 {roles={DM,GM}}。
          //   ★ 它们**不在 GM 桶之外**：本来就在 GM 桶里（{GM}）⇒ 补标签后成"两桶共享"，GM 桶条数不变（179）。
          //   ★ gate() 一个字未改（本来就是 Ask）⇒ 自动成类2 DM_APPROVAL，与用户口径「DM/GM 混合就是要交给 GM 审核」一致。
          //   逐条：造兵（命令面**没有** unit.RaiseUnit ⇒ 移出名单 = DM 彻底不能造兵）、征税（levyRegion 同样是工具独有）、
          //   招吏、辞退、选人、派队、家户成员直调。
          "simos.unit.raiseUnit",
          "simos.gov.recruit",
          "simos.gov.retireStaff",
          "simos.social.household.members",
          "simos.unit.levyRegion",
          "simos.gov.selectExaminees",
          "simos.gov.dispatchTeam",
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
          // ★★ 2026-10-14 决策自由化（批 B）：四件套**改名** sd → ga，并长成**七件套 + 推演确认**。
          //   改名（**不计增**，旧名一律不留 —— Alpha 不兼容旧档）：
          //     simos.sd.propose        → simos.ga.packet.propose
          //     simos.sd.packet.intent  → simos.ga.packet.intent
          //     simos.sd.packet.my      → simos.ga.packet.my
          //     simos.sd.packet.submit  → simos.ga.packet.confirm（"确认本 tick 全部决策"，DRAFT→PENDING）
          //   净新增：revise / drop / reopen（自由改 / 删 / 撤回确认，都只在 DRAFT 上可用）
          //   另加一条推演确认：simos.ga.adjudication.ack（{DM} + gate=ALLOW）。
          "simos.ga.packet.propose",
          "simos.ga.packet.revise",
          "simos.ga.packet.drop",
          "simos.ga.packet.intent",
          "simos.ga.packet.my",
          "simos.ga.packet.confirm",
          "simos.ga.packet.reopen",
          // ★★ 2026-10-14 推演确认（设计书 §3.5）：DM 对"GM 已推演"这件事表态 ⇒ 一条 PutInfo，
          //   零世界效果、零新状态；GM 用 simos.gm.round 看谁确认了。
          "simos.ga.adjudication.ack",
          // ★★ D4 的跨区上报写口 simos.sd.report **已随旧上报族整族废弃**（2026-10-14 决策人互致消息）
          //   ⇒ 它不在本表；取代它的是上面的 simos.ga.message.send（写感知层、闸门 = 事先授权）。
          // ★★ 2026-10-14 AgentLib Docs/Workspace 接入 +6：决策人能自由记录文档
          //   （用户原话「测试一下新agentlib体系能不能让dm自由记录文档」）。
          //   ★ 逐条核过**只有这 6 条**进决策人面：doc_list/read/query/append/create/edit。
          //     · doc_void **不在**（作废别人的记录是 GM 动作）；
          //     · mount_workspace / unmount_workspace / list_workspaces **不在**（工作区由 GM 维护，U1）。
          //   ⇒ 42 → 48。实测来源：真壳 DM_SIZE=48。
          DocsTools.DOC_LIST,
          DocsTools.DOC_READ,
          DocsTools.DOC_QUERY,
          DocsTools.DOC_APPEND,
          DocsTools.DOC_CREATE,
          DocsTools.DOC_EDIT,
          // ★★ 2026-10-14 上游 R8（控制方裁定 §11.3）：两条"能力自述"工具（纯读、DM_FREE）也进决策人面 ⇒ 48 → 50。
          DocsTools.DOC_GUIDE,
          WorkspaceTools.WORKSPACE_INFO);
}
