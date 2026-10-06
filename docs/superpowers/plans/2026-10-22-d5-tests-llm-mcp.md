# D5 施工契约：测试迁移 + 真 LLM/MCP 验收 + 决策回合统一结算

> 批次：D5（规划入口 `docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md`）
> 基线：D4 已推送（`a8abc45d`）。用户 2026-10-22 追加裁定：
> 1. 决策人不是同时跑，而是**同一冻结快照上的同时决策语义 + 可配置物理并发**；
> 2. 架构要适应 LLM 行为：**停止说话**、**提交后继续发命令**、**只写自然语言不出结构命令**都要能结算；
> 3. System 提示词必须存在但**内容是一个空格**（不传会报错），身份与规则走 user 提示；
> 4. after-submit 继续发命令时取**整轮最后一段文本**作为 NL 决策。

## 1. 测试迁移（D5 主体）

- 旧类型退役后的测试迁移：`PopulationDynamicsTest` 删除（机制退役）；`GoodsAccountTest` 改为 `HouseholdInventoryTest`；
  其余 `Recipient`/`ProductionRelation`/`GoodsAccount`/`ClassRow`/`LaborTimeTable`/`manpower` 引用按模块迁移。
- `RealLlmGovScenarioTest` 夹具修复（非场景语义）：
  - `GovCreateOffice` 的 PROVINCE 现在要求至少一个 `regions`；
  - P2-A 后账户主体是家户：`actor.AdjustAccounts` 条目的旧 `owner/q/r` 形状改为 `{household, q, r, goods, money}`；
  - GOV 组合工具从 `hh-gov-<unitId>` 现算人口 ⇒ 夹具先给中央/省两家补成年男丁；
  - `retireStaff` 支付口径修复后，夹具把退休待遇恢复成真实的 20/50 银，不再用 0 绕开；
  - 社交总量守恒检查改为按“家户成员份额”现算（`SocialData.populationAt`），不再逐 lot 全量计数
    （P2-A 后一个 lot 可被多个家户按份额共享，逐 lot 全量会在共享后多计）。
- 旧 `RealLlmGovScenarioTest` 的“逐人 `sd.RunDecision` + `sd.IssueDirective` + GM 立即代执行”顺序链路，已改写为
  **一次 `simos.sd.run-decision-makers` 冻结批次 + 决策包审批 + 一条 `gm.packet.execute` revision**；
  中央/省 DM 都只读同一触发 revision，GM 在所有包收齐后统一执行。

## 2. 决策回合统一结算（用户裁定后的新协议）

### 2.1 system / user

- 发给 provider 的 messages 固定以 `LlmMessage.system(" ")`（一个空格）开头；
- 历史里的 system 消息不再发送；身份、权限、决策规范全部写入首条 user 消息，每轮再插一条不落盘的 user 规则提醒；
- provider 侧“没有 system 就报错”的问题因此不再出现，同时 system 不再承载任何会漂移的内容。

### 2.2 回合内 submit 只登记

- `DecisionAgentRunner` 在决策回合里拦截 `simos.sd.packet.submit`：不立刻把 packet 锁成 PENDING，只记 submit 标记并返回
  “已登记，本轮结束时统一提交；可继续补充命令”；
- `packet.intent` / `packet.propose` 在 submit 标记之后仍可继续写 DRAFT，因此“提交后继续发命令”不会丢内容。

### 2.3 回合结束结算（`DecisionTurnFinalizer`）

runner 返回后由 `DecisionAgentService` 调 `DecisionTurnFinalizer`，按三种分支统一落盘（`CoreSimos.submitBatch`，同批一条 revision）：

1. **没有 submit 就停止说话**：packet 已有的 intent/calls + **整轮最后一段自然语言**合并为最终 PENDING packet；自然语言作为 NL 决策交 GM；
2. **submit 后不再发命令**：已写进 packet 的命令照常交 GM；提交后的纯文本只进审计，不再算新的 NL 决策；
3. **submit 后又继续 propose/intent**：保守合并——整个回合的命令合入同一个 packet，**整轮最后一段自然语言**作为 NL 决策，
   最后统一 submit 一次；不需要真的“取消已提交状态”，因为 submit 在回合内本来只是标记。

- 最终结算批固定追加一条 `sd.PutInfo` 审计（地址 `sd:decision-finalize.<packetId>`，`finalizeReason` ∈
  `auto-no-submit|submit-marker|continued-after-submit|aborted`），GM 看得见“为什么自动结算、最后一段文本是什么”。
- 预算中止/运行时失败时，`DecisionAgentService` 仍调用 `finalizeAborted`：把已经写进 DRAFT packet 的 intent/calls
  保守提交为 PENDING（审计 `aborted`），不让模型跑飞把已产出的内容静默丢掉。
- 同一 Shell 的 finalizer 用同一把锁串行“读 head + 落最终包 + 审计”，并发跑轮（`concurrency=6`）不会互相 Conflict。

### 2.4 旧单人链路

`sd.RunDecision`（单人 `runRound`）与 `run-decision-makers`（`runRoundFrozen`）都走同一套结算；旧 `sd.IssueDirective`
仍按旧语义立即提交，但回合结束的最后一段自然语言同样会作为 NL 决策包交给 GM。

## 3. 其他真 bug 修复

- `GovDismissPlan`：退休待遇不再写死 `availableSilver = 0`；可支配银读 `hh-gov-<unitId>` 政府家户账户
  （`AvailableStock`，缺账 = 0），`actor.AdjustAccounts` 载荷改为 `{household, q?, r?, money}` 家户形状。
  fixture 现在用真实 `retirementPerStaff=20/50` 跑通“扣款 + 回籍 + 逐值守恒”。
- `RunDecisionMakersTool`：批量派发改为冻结回合，`concurrency` 只影响墙钟；packet 写共享锁串行；失败/中止前内容保守结算。

## 4. 证据（2026-10-22）

| 门禁 | 命令 | 结果 |
|---|---|---|
| test-compile | `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests test-compile` | rc=0 |
| 非真 LLM 全量 | `tools/mvn-lock.sh -q -pl simos-app -am -Dtest='!RealLlm*' -Dsurefire.failIfNoSpecifiedTests=false test` | rc=0（`/tmp/final-nonreal.log`） |
| GOV 夹具四工具 | `SIMOS_REAL_LLM=1 SIMOS_GOV_SCENARIO_FIXTURE_ONLY=1 tools/mvn-lock.sh -q -pl simos-app -am -Dtest='RealLlmGovScenarioTest#fixtureOnlySmokeRunsFourGovToolsWithoutLlm' ...` | rc=0（`/tmp/turn-fixture6.log`） |
| 真 LLM 单人 | `SIMOS_REAL_LLM=1 SIMOS_REAL_LLM_ROUNDS=1 tools/mvn-lock.sh -q -pl simos-app -am -Dtest='RealLlmUnitDecisionLoopTest' ...` | rc=0（`/tmp/final-real-unit.log`） |
| 真 LLM 同时决策 GOV | `SIMOS_REAL_LLM=1 SIMOS_GOV_SCENARIO_ROUNDS=1 tools/mvn-lock.sh -q -pl simos-app -am -Dtest='RealLlmGovScenarioTest#multiLevelGovScenario' ...` | rc=0（`/tmp/real-gov-after-lock.log`） |

真 LLM 同时决策矩阵（`/tmp/real-gov-after-lock.log`）关键读数：

```json
{
  "blockers": [],
  "modelAborts": [],
  "modelFailures": [],
  "criteria": {
    "centralAnyPacket": true,
    "centralAnyQualified": true,
    "provincePacket": true,
    "provincePacketAction": true,
    "scenarioToolTypesCount": 2,
    "allFourToolsRun": true
  }
}
```

- 6 个 DM 同一触发 revision（`main@29`），物理并发 6；
- 中央/省 DM 都真产出了 PENDING packet；
- GM `gm.packet.execute` 真执行了包内 approved call（`scenarioToolTypesCount=2`）；
- 四条 A 工具（科举/调查/吸收/退休）在包执行或 GM 确定性补执行下 `allFourToolsRun=true`；
- 范围隔离确定性断言通过，无越权成功。

## 5. 已知债务 / 边界（不静默）

1. **SpotBugs 基线债**：`clean verify` 不带 `-Dspotbugs.skip=true` 仍会在仓库既有基线问题上失败
   （`EI_EXPOSE_REP` 于不可变 record、`UPM_UNCALLED_PRIVATE_METHOD`、`SF_SWITCH_NO_DEFAULT`、
   `FE_FLOATING_POINT_EQUALITY`）。本轮已为 social-api/economy-api/social/unit/actor 补了部分 suppression，
   全量收口留独立批次。
2. **D4 GUI/Catalog 面板**：仍按 D4 约定留 D4.1。
3. **provider 互操作**：真模型偶尔会产出带 JSON `null` 的 `tool_calls.arguments`，AgentLib 解析层会拒整条响应
   （本轮已用 user 规范“args 不要写 null、缺字段就省略”显著降低发生率，真 LLM 1 轮 6 DM 跑已无该失败；
   但这不是 runner 层能完全消除的，记录为 provider 互操作债）。
4. **预算中止**：`DEFAULT_MAX_LLM_CALLS=20` 未改；中止时只保证“已写进 packet 的内容”被保守结算，
   未写进 packet 的模型思考不抢救。真 LLM 1 轮 6 DM 已无 aborted。
5. `RealLlmGovScenarioTest#multiLevelGovScenario` 仍保留 GM 确定性补执行来保证四条 A 工具至少真跑一次；
   模型侧只要求中央/省至少各有一个决策包且至少 1 类工具从包路径执行。

## 6. 文件

- 生产：`DecisionAgentRunner`、`DecisionAgentService`、`DecisionTurnFinalizer`（新增）、
  `RunDecisionMakersTool`、`GovDismissPlan`、`DecisionPacketPayloads`。
- 测试：`DecisionAgentRunnerTest`、`RunDecisionEndToEndTest`、`ResetConversationEndToEndTest`、
  `SdRunDecisionApiTest`、`RejectDirectiveToolTest`、`RealLlmGovScenarioTest`。
- 文档：本文件 + 主计划进度。
