# 紧急 tick 与监控轮次 —— 现状调查 · 2026-10-13

> **触发**：用户 2026-10-13 要求「现在开始调查紧急tick和监控轮次的实现」，并要求先做**现状调查**（未提设计）。
> **用户已裁定的口径**（原话，逐条）：
> 1. 「**紧急tick机制必须做，并且得放在util里声明、覆盖所有包**」
> 2. 「**监控指标事实上是紧急tick的一种触发方法，可以放在APP里面管理**（因为APP包可以看到所有模块，监控所有模块的任意参数……也许吧）」
> 3. 「肯定是要分**紧急tick和日常决策tick**的，世界状态，例如经济模块里本身随着时间推演会产生紧急状态，当紧急状态出现，且时间通过模式为"**推演模式**"（不忽略紧急状态），那么GM就需要先分析紧急状态，然后把处理任务派发给相关决策人」
> 4. 「最好还能搞那种**监控指标，由GM注册/同意注册，DM可提出监控指标**，当监控指标到的时候，同样**停止过时间**，进行紧急命令/推演；默认的**周期性推演**也是有必要的」
> **来源**：四路并行只读调查 + 控制方亲自复核三条关键断言。**未跑 Maven、未起服务**。
> **工作树**：含 Skill 拆除的未提交改动（与本主题无关，未触碰）。

---

## 0. 一句话结论

**"紧急状态"的判定本仓已经有了三套（且有一套真的会在推进中改世界）；但"停下推进"这件事在 Core 里连一个表达位都没有 —— 它只能由推进的调用方用"步长"来实现。**

| 你要的 | 现状 |
|---|---|
| 紧急状态**判定** | 🟢 **已有三套**（详见 §3）：`CrisisMonitor` 5 类灯（只读）· `HexCrisisSignal` 15 类（**落世界状态**）· `OperatorCondition` 连续周期计数器（**推进中累加**） |
| 紧急状态**真的改变推进** | 🟡 **只有一个先例**：市场"粮库存 < 10 天 ⇒ 当天多开一个紧急轮"（`MarketSettlement`） |
| **停下推进** | 🔴 **Core 无任何表达位**：`TimeProposal` / `WorldTimeProposal` / `CommandResult` 三个契约里都没有"停"，`TimeAdvance` 也无取消点 |
| **GM 注册监控指标 / DM 提议** | 🔴 零命中。但有**可复用的现成形状**：`EconomyPolicyKey`（枚举登记 + 缺省值 + min/max 域 + 单一读写口） |
| **定期轮次** | 🟡 `decisionCadenceTicks` → `due` **只有读、不驱动任何东西**；批量派发工具**明确不按 due 自动派** |
| **触发器的结构化形状** | 🟢 **已有**：`Trigger` sealed 五档 + `TriggerEvaluator` **真求值器**，由 `GaTimeParticipant` **逐日**调用（全仓唯一生产调用点） |

---

## 1. ★ 时间推进管线：原子、无中途停车位

### 1.1 一次 `AdvanceTime(N)` = 一条 revision，事务级原子

`TimeAdvance.run` 六步（`simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java`）：

| 步 | 行 | 要点 |
|---|---|---|
| ① Prepare | `:190-240` | 查 head；`range.from` 必须 **等于** base 的时间戳（`:232-240`，连续性硬校验） |
| ② Propose | `:244-251` | **每个参与者只调一次** `simulateWorld(state, cmd.range())` |
| ③ Resolve | `:284-299` | **写-写冲突 ⇒ 拒绝整次推进**（`:286-296`），不是部分成功 |
| ④ Validate | `:312-325` | codec 异常折成 `Rejected`（`:447/:459`） |
| ⑤ Commit | `:327-364` | **唯一落盘点** `:331 timeline.appendRevision(…)` → `Timeline.java:176-183` 单事务（1 行 revision + 全部事件） |
| ⑥ Post-commit | `:366-367` | 按 `revision % checkpointInterval == 0` 写 checkpoint |

★ **`newMeta` 的天数 = 请求的终点**：`:201 StateMeta newMeta = new StateMeta(target, cmd.range().to().orElseThrow())`
⇒ 世界时间戳**无条件**取 `range.to`，与参与者在里面实际走了几天无关。

★ **`CommandResult` 三种、封闭**：`Committed(ref)` / `Rejected(reason)` / `Conflict(current)` —— **没有"实推了几天"、没有"是否被打断"**。

### 1.2 逐日循环在**参与者内部**，Core 看不见

```java
// simos-app/.../app/time/PopulationEconomyTimeParticipant.java:620
for (long day = range.from().tick() + 1L; day <= to.get().tick(); day++) {
```
同形态：`GaTimeParticipant.java:174`。⇒ **"紧急出现在第几天"在循环内部是可见的（`day` 就在那），但循环里没有任何出口**。

★ 权威裁定原文（`docs/superpowers/specs/2026-09-25-aggregate-economy-redesign.md:236-247`，用户原话）：
> 「我点推进100天，它不直接结算到100，而是一天一天地推进！改！」
> `AdvanceTime` 允许 `to > from + 1`（**一次推进 N 天，落一条 revision**）… **结算语义不跳日**
> 判据是**等价性测试**：`advance(from, from+N)` 的**终态** == `N 次单日 advance` 的终态
⇒ ★ **这条等价性是本设计的护栏，也意味着逐日分段推进与单次大区间在终态上不可区分**（只有 revision 数与事件链不同）。这对"停车不影响推演结果"是好消息。

### 1.3 三个推进契约里**都没有**"停"

- `TimeProposal(namespace, changeSet, reads, writes)`（`simos-util/.../util/spi/TimeProposal.java:22-30`）
- `WorldTimeProposal(participantId, moduleChanges, reads, writes)`（同目录；**`moduleChanges` 不得为空**，空提案当**装配错误**抛，`:40-46`）
- `TimeRange(from, to)`（`simos-util/.../util/time/TimeRange.java:11-23`，左闭右开）
- ★ 实测：`grep -rn "abort|pause|halt|suspend|emergency|紧急" simos-util/.../util/spi/ util/time/ util/state/` ⇒ **零输出**
- **"我不提案"的唯一形态 = 不变变更集**（`GaChangeSet.between(base, base)` 等）；`simulateWorld` 返回 `null` ⇒ 判**装配错误**

### 1.4 参与者只有 2 个

`Shell.java:1001-1012` 注册 `PopulationUnitTimeParticipant`（namespace=`population`）+ `GaTimeParticipant`（namespace=`ga`）；
`TimeAdvance` 构造期按 `namespace()` **字典序**排（`TreeMap`，`:146-154`，C25）⇒ 实际调用序 = `ga` → `population`。
★ `EconomyOwnershipTimeParticipant`（`ownership`）**未注册进 Shell**，只有测试夹具用。

---

## 2. ★ 停车只能靠"步长"——代价实测

### 2.1 停车的唯一机制

**"停在 tick T" = 只 `advance(from=T-1, to=T)`**，即**调用方控制每次推进的步长**。
- 没有任何调度器：`grep -rln "ScheduledExecutor|Timer|autoAdvance|tickLoop" simos-*/src/main` ⇒ **无输出**
- 没有 stop/pause 端点（`GuiServer` 里 `pause`/`halt` 零命中）
- **每个 tick 边界都是一个完整 revision**（parent 指向前一 tick），续推只需 `{expectedRevision: head, from: 已停 tick}`
- 读态靠 `Replay.replay(target)`：沿父链找最近可用 checkpoint，缺失**不失败**、继续向创世回退（C18）

### 2.2 逐 tick vs 单次大 range 的实测倍率

| 读数 | 数字 | 出处 |
|---|---|---|
| `0→360` 单次 | **10.65 s** | `.superpowers/sdd/2026-10-11-w9f-test-batch/logs/sw360.result.json:61` |
| `0→360` 逐 tick | **43.675 s** | 同目录 `swtick.result.json` |
| **倍率（同世界同批次）** | **4.10×** | 43.675 / 10.65 |
| `0→1000` 单次 | **15.373 s** | `.superpowers/sdd/2026-10-11-w2-acceptance/report.md:314` |
| 逐日 400 天（1000→1400） | **152.611 s** | `.superpowers/sdd/2026-10-11-w8-debt-semantics/results/dep1000to1400.result.json:9850` |
| **倍率（400 天窗口）** | **≈9.93×（保守上界）～ 24.8×（线性外推）** | 单次 400 天**未实测** |

★ **真正的坑在"世界越大越贵"**：`152.611/400 = 0.3815 s/天`（day 1000+）vs `15.373/1000 = 0.01537 s/天`（0→1000 单次）= **24.8×/天**；
而同为逐 tick 的 `0→360` 只要 `0.1213 s/天`。⇒ **1825 天逐日不能按 0.12 s/天 外推**。

### 2.3 逐 tick 的副作用体量（★ 这条会决定成败）

| 量 | 读数 | 出处 |
|---|---|---|
| 每 tick changeset | day 1000–1400：`min 253,813 / p50 292,970 / p90 540,485 / max 2,421,557` B | 同上 result.json `revisions[]` |
| 段内变更集合计 | **142.70 MB / 401 天** | 同上 |
| 逐 tick 的 revision 数 | `swtick` Δrev=360（361 条）；`dep1000to1400` Δrev=1400（1401 条） | 同上 |
| 逐 tick 的 checkpoint 体量 | **1,328 MB**（361 个 checkpoint，均值 3.86 MB）+ 变更集 124.8 MB ≈ **1.45 GB** | `.superpowers/sdd/2026-10-11-w9f-test-batch/impl-ledger.md:310/358` |
| checkpoint 间隔 | 缺省 **100**（`ShellConfig.java:50`）；★ 长跑装置实测配置 = **1**（`w8-debt-semantics/scripts/shell-sw.json`） | — |
| day 1400 单档 checkpoint | **18.42 MB** | `w8-debt-semantics/stores-final/d690-tick1400.json` |

⇒ **1825 tick 逐日 = 1826 条 revision**（外推）；`checkpointInterval=1` 下从 360 tick 的 1.45 GB 外推到 1825 tick 是 **数十 GB 级**。
⇒ ★ **逐 tick 停车必须同时定 checkpoint 间隔**，否则先死在磁盘上（不是 CPU 上）。

---

## 3. ★ "紧急状态"的判定：已有三套，其中一套真的会改世界

### 3.1 `CrisisMonitor`（只读探针，**唯一生产消费点是 GUI 视图**）

`simos-app/.../app/crisis/CrisisMonitor.java`（348 行）：
- 五类灯：`FOOD(粮满足率<900‰)` / `CLOTH(<500‰)` / `MORTALITY(死亡率>20‰周期)` / `DEBT(新借入≥需求250‰)` / `LABOR_BURDEN(占用率≥950‰)`（`:68/71/74/77/80`）
- 每盏灯带**当场量到的数字**作依据（`evidence`），类注逐字：「它输出的是**类别**，不是一个概率」
- 类注逐字：**「本类只读，不产生任何命令、事件或状态改动」**
- ★ **控制方实测复核**：`git grep -rn "CrisisMonitor\." -- 'simos-*/src/main/**'` ⇒ **只有 2 行，都在 `ApiViews.java:5616-5617`**（塞进单格人口视图的 `"crisis"` 键）。全格批量口 `lights(...)` **零调用方**。前端 `webui` 里 `crisis` **零命中**、MCP 读工具描述里也没提它。
- 阈值是**编译期常量**；类注自认「V7 参数目录落地后迁入并成为 GM 可调」

### 3.2 `HexCrisisSignal`（★ **落世界状态**，第 27 个组件）

- `simos-economy/.../economy/model/HexCrisisSignal.java`，`Kind` **15 项**（FOOD/CLOTH/MORTALITY/DEBT_EXPLOSION/CLASS_DECLINE/INPUT_SHORTFALL/LABOR_BURDEN/ADMIN_*/GOV_SERVICE_DESERTION）
- **推进中写入**（关账日）：`EconomySettlement.java:3034 → :3050` → `EconomyLiquidationSettlement.java:1208 writeCrisisSignals`；GOV 侧 `GovernmentServiceDesertionBridge.java:481`
- 阈值 `EconomyLiquidationSettlement.java:94 DEBT_STRESS_CYCLES_THRESHOLD=2`、`:101 DEBT_EXPLOSION_F_MULTIPLE=4`；类注逐字：「它是 hex 警告的**读数阈值，不是自动政治行动**」
- 消费方全是读口（`ApiViews` economyHex / dashboard / gov 工具）

### 3.3 `OperatorCondition`（连续周期计数器，**推进中累加**）

- `simos-economy/.../economy/model/OperatorCondition.java:48`（第 13 个组件），四个计数器：`unsoldCycles` / `shortfallCycles` / `debtStressCycles` / `suspendedCycles`
- **累加点**：`OperatorSettlement.java:342-349/421-422/513`，由 `EconomySettlement.java:2938` 调，闸门 `:2932 if (anyCycleClosed)`（**只在关账日前进**）
- 阈值全在 `StressPolicy.java:19/25/36/49/67/70/73/76`（编译期常量）
- **状态转移会改世界**：缩产系数 `StressPolicy.java:83 CONTRACTING_SCALE_PER_MILLE=500`、`:86 INDEBTED_SCALE_PER_MILLE=250`

### 3.4 ★★ 唯一"异常判据 → 推进中真的改变世界"的先例

```java
// simos-economy/.../economy/time/MarketSettlement.java:1418-1420
if (day % 5 == 3 && lowGrainStock(round, topology)) return MarketTrigger.LOW_GRAIN_STOCK;
```
- 阈值 `:199 MARKET_LOW_STOCK_TRIGGER_DAYS = 10L`、`:205 MARKET_LOW_STOCK_EXTRA_PHASE_DAYS = 3L`
- 判据 `:1437-1461 lowGrainStock` **逐行判、不是逐格求和**（注释 `:1428-1431`：「同一格上'有粮的富裕户'若把缺口户平均掉，紧急轮就永远不开」）
- 消费点 `EconomySettlement.java:2169 → :2187 if (marketTrigger != MarketTrigger.NONE)` ⇒ **当天真的多撮合一"紧急轮"**
- 枚举 `MarketTrigger.java:17-25`：`NONE / PERIODIC / CYCLE_CLOSE / LOW_GRAIN_STOCK`
  ★ 它的类注逐字把 `LOW_GRAIN_STOCK` 定义为「例行轮之外的**追加轮**」—— 这就是"紧急轮"的既有语义。

⇒ ★ **这是本仓唯一一处"阈值判据在推进中被求值，并当场改变世界"的形状。你要的紧急 tick 在语义上是它的推广**（从"经济内部多开一轮"推广到"世界停下来让 GM 介入"）。

### 3.5 触发器的结构化形状：**已有，不需要新建**

- `simos-ga/.../effect/Trigger.java:42-49` —— `sealed interface` + `@JsonSubTypes`，**五档**：
  `at_or_after_tick` / `after_ticks` / `unit_at_hex` / `and` / `or`
- `Action.java:37-42` —— `sealed interface`，**两档**：`put_info` / `enqueue_unit_command`（`SetStage`/`RecordCasualties` 已退役）
- ★ **求值器是真的**：`TriggerEvaluator.evaluate(trigger, at, units, sinceTick)` 纯函数（`TriggerEvaluator.java:32-59`），
  **唯一生产调用点** `GaTimeParticipant.java:191`，**逐日**求值（`:174` 日循环内）
- `Effect` 六字段：`id / kind / trigger / action / status / createdTick`（`Effect.java:22-28`）；`EffectKind` 五档、`EffectStatus` 五档
- ★ **两个真缺口**：
  1. `EffectStatus.EXPIRED` **全仓 src/main 零写者** ⇒ **没有到期机制**，效果只有 `FIRED` / `CANCELLED` 两个出口，永假 trigger 永久停在 `PLANNED`
  2. **效果本体在任何读口不可达**：`GaResolver` 只认 `decision-maker` / `directive`（`GaResolver.java:35` 明写 "`effect` 随 3b-4 删"）⇒ GUI / MCP resolve 面上看不到效果
- ★ survey #12 成立：`CatalogTool.java:813` 只列 `"effectId, kind(五档), trigger, action, createdTick?"` ——
  `kind` 有枚举，**`trigger`/`action` 只有字段名**，子类型名只存在于注解与 javadoc（模型看不到）⇒ 模型无法知道该写 `{"@class":"after_ticks",...}`

---

## 4. 监控指标 / 定期轮次：形状可复用，机制不存在

### 4.1 "可注册参数"的现成形制 = `EconomyPolicyKey`

- `simos-economy/.../economy/model/EconomyPolicyKey.java:24` —— 枚举登记项，**每键带 `描述 + defaultValue + minValue + maxValue`**
  例 `:33 FAMINE_MAX_PPM_PER_TICK("饥荒死亡率上限（ppm/tick）", 1_000L, 0L, 1_000_000L)`
- 读口**唯一拼写点** `EconomyMeta.java:127 economyPolicyOf(key)`（表里没有 ⇒ defaultValue）；构造期越域守卫 `:92-95`
- GM 写口 `EconomySetEconomyPolicyTool.java:21`（`economy.SetEconomyPolicy`）
- 类注 `:12-14` 逐字：它解决的正是「这些数过去是**编译期常量**」
⇒ ★ 它的形状 = **枚举登记项 + 缺省=现值 + min/max 域 + 单一读写口**，与你要的"GM 可注册的监控指标"最接近。

### 4.2 无通用指标读口

- `SimulationState.module(namespace)` 返回 `Optional<Snapshot>`（`simos-util/.../state/SimulationState.java:31-33`）⇒ **机制上 APP 确实能取任意模块**
- 但**"读出来"全是手写的**：`ApiViews.java:5986-6076` 七个 `instanceof` 断言提取器（map/unit/gov/army/social/economy/actor）
- 反例：`ApiViews.heatmap(state, metric)` 是**封闭 switch + 8 个硬编码常量**（`ApiViews.java:689-712`，常量在 `:215-222`），未知 id ⇒ 具名 `unavailable`（不填 0）
⇒ **"按名字取任意数字"的通用读口不存在。**

### 4.3 `due` 是纯展示位（复核确认）

- 派生 `SdQueryService.java:352-367`：`due = (tick − 该 dm 最近一次 Directive 的 tick) ≥ decisionCadenceTicks`，首次恒 due；类注逐字「**只读派生**」
- GUI 双口径：**「开始决策」按钮不因 `due` 禁用**（`panels.js:1343` 只判 `!hasTarget`）；`panels.js:1301-1302` 注释逐字「**`due` 不再是闸门，只是提示**」（用户 2026-09-23 裁定）
- MCP 批量口 `RunDecisionMakersTool.java:101-103` 逐字：「**due 仅展示**…不按 due 自动筛选/自动派出；due 与前置行政能力/军令门**均不作为本工具的选择条件**」（用户 2026-10-01 裁定 8）
- `DecisionDueRunner` **全仓 0 命中**

### 4.4 "轮次"没有载体

| 候选 | 是什么 | 是不是"轮" |
|---|---|---|
| `Directive` | per (dm, tick)，**同一 (dm,tick) 可多条**（末位生效） | 「一次决心」的账，不是轮 |
| `DecisionPacket` | per (dm, tick) **一个包**，id 拼法 `pkt-<dm>-<tick>`（四处工具同拼法） | 一轮的**产物**，不是轮的实体 |
| `DecisionTurn` | 跑一轮 LLM 的账（`llmCalls`/`toolInvocations`），**不落 revision** | 是"跑一轮"，没有 tick 归属 |
| 经济 120 天周期 | 产业生产周期（`CYCLE_DAYS=120`） | 不是推演轮 |

⇒ **全仓唯一的轮次轴是 `tick`**；`Cycle` / `Period` 在 `simos-ga/src/main` **0 命中**。

---

## 5. ★ 三条必须知道的地雷（不踩则白跑）

| # | 地雷 | 事实 | 后果 |
|---|---|---|---|
| **L1** | **`advanceAndDrain` 在生产路径零调用** | `Shell.advanceAndDrain`（`Shell.java:1533`）只有 5 个测试类调；`/api/advance`（`GuiServer.java:1702`）与 `simos.advance`（`AdvanceTool.java:123`）**都直接 `core.submit`** | `Action.EnqueueUnitCommand` 类效果**永不落地**（`GaTimeParticipant` 有意忽略它 `:304-306`，交 drain）。★ 缓解：`new Action.EnqueueUnitCommand(...)` 在 main 里**零构造点** ⇒ 今天若没人用 GM 工具注册过这种效果，损失是 0；**一旦注册过，效果 FIRED 而世界纹丝不动，且无自动重试** |
| **L2** | **自动停车不能漏 drain** | 同上：停在 tick n 时，第 n 天 FIRED 的 `EnqueueUnitCommand` 仍悬在 `ga.effects` 里 | 停车机制必须把 drain 一起做，否则每停一次多一条悬空效果 |
| **L3** | **逐 tick 的磁盘体量** | 见 §2.3：360 tick 逐 tick（`checkpointInterval=1`）= **1.45 GB**；day 1400 单档 checkpoint = **18.42 MB** | 1825 tick 逐 tick 会到**数十 GB 级** ⇒ 逐 tick 停车**必须**同时定 checkpoint 间隔 |

★ 另一条**文档错引**（不影响代码，但派单时会误导）：`TimeParticipant.java:7/:13` 与 `WorldTimeProposal.java:12` 引的
`POLITICAL_ECONOMY_DESIGN.md §9` 实为「**## 9. 观测**」（`:638`），与时间推进无关；相关的是 `§4.2 每日结算`（`:416`）与 `§11` 末条（`:678`）。

---

## 6. 差距清单（按"挡不挡得住目标"排）

| # | 差距 | 挡住什么 | 现状证据 |
|---|---|---|---|
| **D1** | **Core 无"停车"表达位** | 你要的全部（紧急 tick / 监控指标停车） | §1.3：三个契约零"停"语义；`TimeAdvance` 无取消点 |
| **D2** | **紧急状态判定不接推进** | "世界出现紧急状态就停" | §3.1：`CrisisMonitor` 唯一消费点是 GUI 视图 |
| **D3** | **无监控指标注册机制** | "GM 注册 / DM 提议指标" | §4.1：只有 `EconomyPolicyKey` 形状可复用 |
| **D4** | **无通用指标读口** | "监控所有模块的任意参数" | §4.2：七个手写提取器 + 封闭 switch |
| **D5** | **`due` 不驱动派发** | "默认的周期性推演" | §4.3：只有读、无 `if due` |
| **D6** | **effect 无到期机制 + 读口不可达** | 若用 effect 做"触发规则" | §3.5：`EXPIRED` 零写者；`GaResolver` 删了 effect 族 |
| **D7** | **drain 断裂**（L1） | 停车后的世界一致性 | §5 |
| **D8** | **逐 tick 磁盘体量**（L3） | 逐 tick 停车的可行性 | §2.3 |

---

## 7. 待裁定（设计层，需你拍）

1. **D1 的落点**：你已裁"紧急 tick 声明放 `util`、覆盖所有包"。但事实是 —— **`util` 里的三个推进契约（`TimeProposal`/`WorldTimeProposal`/`TimeRange`）都是留给参与者的，Core 的 `AdvanceTime`/`CommandResult` 不在 util 里**（`util` 不认识 Core 类型）。
   ⇒ 声明放 util 有两种读法：**(a)** 参与者在提案里上报"我发现了紧急状态"（改 `util` 契约，Core 读）；**(b)** 只在 util 定义**紧急状态的类型/枚举**，停车的判定与执行留在 Core/app。
   ★ 两者的改动面差一个量级，我需要你明确是哪一个。
2. **停车粒度**：逐 tick（正确但贵 4.1~24.8×、且磁盘要另定间隔）还是**固定分段**（便宜，但会**漏掉区间内出现又消失的紧急状态**）？
   你原话是"当紧急状态出现…停止过时间" ⇒ 字面要求逐 tick 或"推进器内部可中断"。
3. ~~**"推演模式"的载体**~~ ⇒ ★ **已作废**：用户 2026-10-13 裁定「**不许用额外分开，停下就是停下**」⇒ **没有模式开关**（见 §7 的 G3 行）。
4. **紧急状态的定义权**：`CrisisMonitor` 的 5 类（阈值编译期常量）先接？还是直接上"可注册指标"（`EconomyPolicyKey` 形状）并把 5 类当作第一批内建指标？
5. **停车之后谁继续**：外部 GM Agent（你已定 MCP 路线）还是 Core 内部？这决定 D1 的落点。

---

## 7. 口径裁定（用户 2026-10-13 已拍；★ 本节取代前文的待裁定措辞）

| # | 口径 | **裁定** |
|---|---|---|
| **G3** | **"紧急 tick vs 日常决策 tick"要不要一个模式开关** | ★ **不许**。用户原话：「**不许用额外分开，停下就是停下**」⇒ **没有任何"推演模式"开关**；突发事件命中就停，无条件。**本报告 §7 原先写的"需要一个模式开关"作废** |
| **G1** | 停下之后"把处理任务派发给相关决策人" | ★ **不是本仓的功能**。用户原话：「**派单给决策人是GM自己决策的**，我说白了GM自己想瞒着那也没办法」⇒ Core 只负责"停下 + 报原因"，**派不派单由外部 GM Agent 自己决定** |
| **G2 + G9** | 监控指标 / 紧急状态判定 | ★ 用户要的是**先做调查**：「因此需要给各个模块补条件，**有办法在APP搞个通用监控方法吗？也许没有，只是调查**」⇒ 下一批 = **调查 APP 层能否搞通用监控方法**，不是实现。另：**G9 与 G2 一起考察** |
| **G4** | 决策人对 GM 推演的异议/确认轮 | ★ 用户：「**详细规划一下**」⇒ 待出一份规划（尚未做） |
| **G5** | 决策人私有文档系统 | ⏸ 用户：「**隔壁在干**」⇒ 挂起，等隔壁 AgentLib |
| **G6** | 3~5 年长跑编排器 | ⏸ 用户：「**急啥？**」⇒ 不是当前优先级 |
| **G7** | GUI 可见推演内容（事件时间线 / 日志流 / LLM 推理） | ★ 用户：「**这玩意确实挺紧急**」⇒ 优先级**高** |
| **G8** | 换更大的地图 | ⏸ 用户：「**这个真别急**」⇒ 不急 |

★ 另：用户 2026-10-13 指令「**先把目前手头上的活干完吧，删删旧代码补补测试之类的，补完再说**」
⇒ 紧急 tick 的后续（G2/G4/G7）**排在"补完手头活"之后**。

---

## 8. 未核 / 如实记

1. **未跑 Maven / 未起服务**：一切结论来自源码 + 既有实测证据文件，**无运行期验证**。
2. **`0→360` store 的体量未能复现为 14 MB**（实测 `simos.db` 6,299,648 B + `checkpoint/main/1.json` 990,505 B ≈ **7.0 MB**，`freelist_count=0`、无 WAL 残留）。前一份调查书（`2026-10-13-long-run-agent-simulation-recon.md:66`）写的 14 MB **未写取数命令** ⇒ 两存待核。
3. **1000→1400 的"单次大 range"对照从未实测** ⇒ §2.2 的 9.93× 用的是"1000 天单次"（更长更贵）当上界；24.8× 是线性外推。真值未测。
4. **152.6 s 里 HTTP / snapshot 开销占比未拆**：`/api/advance` 无 per-request 计时埋点；逐日那 400 天含 400 次往返 + 400 次 `snapshot`+`prune_cp`。
5. **1825 tick 的一切数字都是外推**（revision 数、GB 数、恢复步数）。既有最长实测 = **day 1400**。
6. **`EnqueueUnitCommand` 的"今天损失是不是 0"无实测支撑**：只核了 Java 源码零构造点，**未核**世界档 / GM 工具调用记录里是否真注册过这种效果。
7. **`checkpointInterval: 1` 是否只出现在 W8/W9f 两处装置** 未逐一核更早批次；`config/shell.json` 未设该项 ⇒ 缺省 100。
8. **"停"只能靠杀进程**这一条是从代码结构判断（无 cancel 口），**未做真机中断实验**。
9. **未核** `HexCrisisSignal` 的 15 个 Kind 里其余 kind 的生产写入者（只核了 `DEBT_EXPLOSION` 与 `GOV_SERVICE_DESERTION`/`ADMIN_*`）。
10. **未核** `CrisisMonitor.lights(...)` 全格口是否被 `src/test` 引用（本调查只扫 `src/main`）。
