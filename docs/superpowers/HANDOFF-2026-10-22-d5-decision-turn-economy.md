# HANDOFF 2026-10-22：D5 决策回合统一结算 + 经济系统观测状态

> 恢复入口。当前 `main` 工作区干净，最后提交：`7d944911`（已 push `origin/main`）。
> 主计划：`docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md`。
> D5 施工记录：`docs/superpowers/plans/2026-10-22-d5-tests-llm-mcp.md`。

## 1. 今天完成了什么

### 1.1 决策人回合统一结算（用户 2026-10-22 裁定）

- 所有决策回合（`sd.RunDecision` 单人 + `run-decision-makers` 批量）：
  - 发给 provider 的 messages 固定以 `LlmMessage.system(" ")` 开头；system 不省（省了会报错），但内容只是空格；
  - 历史里的 system 不再发送；身份、权限、决策规范全部走首条 user 消息 + 每轮 user 规则提醒。
- `simos.sd.packet.submit` 在决策回合内**只登记**，不立即锁死 packet；回合结束由 `DecisionTurnFinalizer`
  统一结算，一个 DM × 一个 tick 只产生一个最终 PENDING packet。
- 三种结算分支：
  1. 没 submit 就停止说话 ⇒ packet 已有 intent/calls + **整轮最后一段自然语言**自动作为 NL 决策交 GM；
  2. submit 后不再发命令 ⇒ 之前命令照常审批，提交后的纯文本只进审计，不再算新 NL；
  3. submit 后又继续 propose/intent ⇒ 保守合并整个回合，所有命令 + **整轮最后一段自然语言**，统一 submit 一次。
- 预算中止 / 运行时失败：`DecisionTurnFinalizer.finalizeAborted` 把已经写进 DRAFT packet 的内容保守结算给 GM，不静默丢掉。
- 并发跑轮：finalizer 用同一把锁串行“读 head + 落最终包 + 审计”，`concurrency=6` 不会互相 Conflict。
- 审计：每次结算追加一条 `sd.PutInfo`，地址 `sd:decision-finalize.<packetId>`，含 `finalizeReason`
  （`auto-no-submit|submit-marker|continued-after-submit|aborted`）与最后一段文本。

### 1.2 真 LLM / MCP 验收

- `RealLlmUnitDecisionLoopTest`：`SIMOS_REAL_LLM=1 SIMOS_REAL_LLM_ROUNDS=1` → `rc=0`。
- `RealLlmGovScenarioTest#multiLevelGovScenario`：`SIMOS_REAL_LLM=1 SIMOS_GOV_SCENARIO_ROUNDS=1`，
  1 tick、6 个 DM、`concurrency=6` → **rc=0**：
  - `blockers=[]`、`modelAborts=[]`、`modelFailures=[]`；
  - `centralAnyPacket=true`、`centralAnyQualified=true`、`provincePacket=true`、`provincePacketAction=true`；
  - `scenarioToolTypesCount=2`、`allFourToolsRun=true`；
  - 无越权成功。
- 证据日志（本地 `/tmp`，可能随机器重启丢失）：
  - `/tmp/real-gov-after-lock.log`（同时决策 GOV）
  - `/tmp/final-real-unit2.log`（真 LLM 单人）
  - `/tmp/final-nonreal2.log`（非真 LLM 全量）

### 1.3 顺手修掉的真 bug

- `GovDismissPlan` 退休待遇写作 `availableSilver = 0`：现在读 `hh-gov-<unitId>` 政府家户账户
  （`AvailableStock`，缺账 = 0），`actor.AdjustAccounts` 载荷改 `{household, q?, r?, money}` 家户形状；
  夹具恢复真实 `retirementPerStaff=20/50` 并跑通扣款 + 回籍 + 守恒。
- `RealLlmGovScenarioTest` 夹具修复：PROVINCE 的 `regions`、P2-A 家户账户形状、政府家户补人、
  社交守恒按家户成员份额现算（修共享 lot 全量多计）。
- `RunDecisionMakersTool` 批量派发改走冻结回合；`DecisionPacketPayloads.submit` 提升为 public。

## 2. 经济系统目前观测到的状态

已跑：
- 非真 LLM 全量（含 `simos-economy` 全部探针）：`rc=0`；
- `SevenHexFullChain3650Test` + `SevenHexNatural3650Test`：2 tests / 0 failures；
- `NaturalMortalityProbeTest` + `LongRunMortalityProbeTest`：2 tests / 0 failures。

结论（不是平衡性结论，只是机制读数）：
- 3650 tick 全链路能持续产出粮/纤维/布/工具并成交，生产、市场、运输、商号结算都真的接上了。
- 开启迁移后，人口从固定租佃向家庭农场、手工业、雇农方向迁移（手工业 +137）；自然世界也向手工业迁移（+60），
  说明“经济信号 → 人口迁移”反馈生效。
- 自然分布世界的锚债能还清；production-only 与 migration 世界的内部债务长期累积，代表信用机制能运转，
  但当前参数下内部债务不自动收敛，是后续校准点。
- 出生/死亡探针：粮足时 4 年 1000→1020（死亡 100、出生 120）；饥荒压力下人口会快速下降到低点并核销债务
  （510→29，累计死亡 481、debtDeleted 10.88M）。说明 Social 生死引擎与经济反馈联动。

边界：
- 7hex 探针世界的总人口是构造为恒定的（P0 人口桥接），所以那几张表只证明“模式间迁移 + 产能”，
  不证明出生/死亡下的长期人口平衡；
- 生死表来自合成 `ProbeEconomy`，不是真实世界；
- 还没有“真世界 Social 生死 + Economy 产能”联合逐 tick 表；
- 真 LLM 场景只覆盖治理/决策流程，没有做长期经济模拟。

## 3. 当前门禁状态

| 门禁 | 状态 |
|---|---|
| `tools/mvn-lock.sh -q -pl simos-app -am -DskipTests test-compile` | ✅ |
| 非真 LLM 全量 `-Dtest='!RealLlm*'` | ✅ rc=0 |
| 真 LLM 单人 `RealLlmUnitDecisionLoopTest` | ✅ rc=0 |
| 真 LLM 同时决策 GOV（1 tick / 6 DM / 并发 6） | ✅ rc=0 |
| `clean verify`（不带 `-Dspotbugs.skip=true`） | ❌ SpotBugs 基线债，见下 |

## 4. 已知债务 / 未完成

1. **SpotBugs 基线债**：`clean verify` 在仓库既有问题上失败（`EI_EXPOSE_REP` 于不可变 record、
   `UPM_UNCALLED_PRIVATE_METHOD`、`SF_SWITCH_NO_DEFAULT`、`FE_FLOATING_POINT_EQUALITY`）；本轮补了部分 suppression，
   全量收口未做。
2. **D4.1 GUI/Catalog 面板**：仍未做（D4 已明确留出）。
3. **provider 互操作**：真模型偶尔会产 `tool_calls.arguments` 含 JSON `null`，AgentLib 解析层拒整条响应；
   已用 user 规范“args 不要写 null、缺字段就省略”显著降低，真 LLM 1 轮 6 DM 已无该失败，但 runner 层不能完全消除。
4. **经济平衡**：尚未做“真世界 Social+Economy”长期联合校准/联合表。
5. `RealLlmGovScenarioTest` 仍保留 GM 确定性补执行来保证四条 A 工具至少真跑一遍；模型侧只要求至少 1 类工具
   从包路径执行（本轮实测 2 类）。

## 5. 恢复命令

```bash
cd /home/cna/SimulatorMosire
git status --short --branch
git log --oneline -5

# 编译
tools/mvn-lock.sh -q -pl simos-app -am -DskipTests test-compile

# 非真 LLM 全量
tools/mvn-lock.sh -q -pl simos-app -am -Dtest='!RealLlm*' -Dsurefire.failIfNoSpecifiedTests=false test

# 真 LLM 单人（1 轮）
SIMOS_REAL_LLM=1 SIMOS_REAL_LLM_ROUNDS=1 tools/mvn-lock.sh -q -pl simos-app -am \
  -Dtest='RealLlmUnitDecisionLoopTest' -Dsurefire.failIfNoSpecifiedTests=false test

# 真 LLM 同时决策 GOV（1 tick / 6 DM / 并发 6）
SIMOS_REAL_LLM=1 SIMOS_GOV_SCENARIO_ROUNDS=1 tools/mvn-lock.sh -q -pl simos-app -am \
  -Dtest='RealLlmGovScenarioTest#multiLevelGovScenario' -Dsurefire.failIfNoSpecifiedTests=false test

# 经济探针
tools/mvn-lock.sh -pl simos-economy -am \
  -Dtest='SevenHexFullChain3650Test#sevenHexFullChain3650,SevenHexNatural3650Test#sevenHexNatural3650' \
  -Dsurefire.failIfNoSpecifiedTests=false test
tools/mvn-lock.sh -pl simos-economy -am \
  -Dtest='NaturalMortalityProbeTest,LongRunMortalityProbeTest' \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

## 6. 关键文件

- 生产：
  - `simos-app/src/main/java/io/mosire/simos/app/decision/DecisionAgentRunner.java`
  - `simos-app/src/main/java/io/mosire/simos/app/decision/DecisionAgentService.java`
  - `simos-app/src/main/java/io/mosire/simos/app/decision/DecisionTurnFinalizer.java`（新增）
  - `simos-app/src/main/java/io/mosire/simos/app/tools/write/RunDecisionMakersTool.java`
  - `simos-app/src/main/java/io/mosire/simos/app/tools/write/GovDismissPlan.java`
- 测试：
  - `DecisionAgentRunnerTest`、`RunDecisionEndToEndTest`、`ResetConversationEndToEndTest`、
    `SdRunDecisionApiTest`、`RejectDirectiveToolTest`、`RealLlmGovScenarioTest`、`RealLlmUnitDecisionLoopTest`
  - `simos-economy`: `SevenHexFullChain3650Test`、`SevenHexNatural3650Test`、`NaturalMortalityProbeTest`、
    `LongRunMortalityProbeTest`
- 文档：
  - 本文件
  - `docs/superpowers/plans/2026-10-22-decision-packet-household-query-llm-mcp-plan.md`
  - `docs/superpowers/plans/2026-10-22-d5-tests-llm-mcp.md`

---

## 7. 2026-10-23 补记：全项目「计划内未实现功能」盘点

- 本轮**未改任何代码**；只做只读回代码核对 + 文档更正。
- 完整现状（完全未做 / 部分实现 / 已作废 / 已补齐 / 待用户裁定）：
  `docs/superpowers/status/2026-10-23-planned-not-implemented-inventory.md`。
- 同时更正：主计划 §10 复选框（D0~D4 漏勾）、`AGENTS.md` 的 P4b 行、
  `docs/superpowers/plans/2026-10-09-p2-backend-fixes.md` 头注「P2 尚未开工」、
  `docs/superpowers/plans/2026-10-22-d2-decision-packet.md` 的 `simos.unit.setArmyPayPolicy` 指向、
  `docs/superpowers/plans/2026-10-02-undeveloped-features.md` 追加 2026-10-23 复核更正节。
- 盘点基线 = 本文件所在提交 `bdf417e6`；工作树干净、与 `origin/main` 同步。
