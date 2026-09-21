# SDSimos 阶段 C（Combat）执行报告

> 分支 `sd/c`，worktree `.claude/worktrees/sdc`，基线 `a13266d`（unit-ext T10 合并后）。
> 计划：`docs/superpowers/plans/2026-09-20-sd-simos-plan.md` 阶段 C（C1~C6）+ §〇 通则。
> spec：`docs/superpowers/specs/2026-09-20-sd-simos-design.md` §八/§十一（N14）。
> 证据：`.superpowers/sdd/2026-09-20-sd-simos/`（`c0-baseline/`、`c-verify/`、`mutants/`）。

## 一 门禁（实测，第 1 次尝试）

`./mvnw clean verify`（前台，独占）**rc=0**，日志 `c-verify/clean-verify.log`、`verify-rc.txt`。

- 模块 **8/8 SUCCESS**（显示名）：`SimulatorMosire / UtilSimos / MapSimos / SocialSimos / UnitSimos / CoreSimos / SDSimos / SimosApp`。
- 用例 **1232** = `util 170 / map 362 / social 45 / unit 259 / core 177 / sd 91 / app 128`。
- `BugInstance size is 0` **×7**；`[ERROR]` **0 行**；前端 `[frontend-gate] OK tests=90 pass=90 fail=0`。
- 基线（本树自测，`c0-baseline/`）：**1199** = `.../ sd 62 / app 124`，同样 8/8、BugInstance ×7、ERROR 0、前端 90/90。
- **delta 干净**：util/map/social/unit/core **逐字不变**；`sd 62→91（+29）`、`app 124→128（+4）`，合计 +33 = 1232。

## 二 各步落地与判据

### C1 Combat 场/阶段/结局命令（`sd.CreateCombat` / `sd.AddCombatStage` / `sd.SetStageOutcomeTable`）

- 新文件：`sd/spi/CreateCombatHandler`、`AddStageHandler`、`SetOutcomeTableHandler`、`sd/model/CombatStages`。
- 判据：链式条件相等 ⇒ 提交成功、不等 ⇒ 拒绝（命令级理由 `AddCombatStage：…`）；权重 ≤0 / 空表 ⇒ 拒绝；
  `Combat.participants` 与 `CombatState.participants` **两层**；`sd.CreateCombat` 参与单位不存在 ⇒ 拒绝。
- 测试 9 条（`SdCombatHandlersTest`）。

### C2 `sd.CommitCombatOutcome`（N2 恰一个）

- `sd/spi/CommitOutcomeHandler`。判据：合法结局 ⇒ `selectedOutcome` 恰一个；**不在该阶段表里（可在别的阶段表里）** ⇒ 拒绝（该守卫独有判别面）；
  已选过 ⇒ 拒绝（不覆盖）。同一坐标两次重放逐字节相同在 **C6** 端到端实测。测试 3 条。

### C3 `sd.RecordCasualties`（N3 双轨 + 上界）

- `sd/spi/RecordCasualtiesHandler`；上界读 **unit 切片**当前值（只读，铁律 3）；`LossRecord.atRevision` 落 revision。
- 判据：Δ 为正 / |Δ| > 当前 ⇒ 拒绝；**恰好 |Δ| = 当前 ⇒ 接受**（差一杀点）；未知装备键 ⇒ 拒绝；`LossRecord` 逐值可回放。测试 7 条。

### C4 Effect 命令 + `SdTimeParticipant`（R6 + N1）

- 新文件：`sd/time/SdTimeParticipant`（`namespace="sd"`）、`sd/time/TriggerEvaluator`、`sd/spi/RegisterEffectHandler`、`CancelEffectHandler`。
- R6：条件未达成不产生、达成后**只产生一次**；N1：`AtOrAfterTick` 与 `ThresholdKills` **各一条**推进；
  `reads/writes` 显式 canonical 集合；`range.to` 缺省 ⇒ 零变更、不抛；**恰一个 sd participant**（注册第二个 ⇒ `TimeAdvance` 构造期抛）。
- 测试：`SdTimeParticipantTest` 10 条 + `SdTimeParticipantWiringTest`（app）1 条 + `ShellEndToEndTest` 的推进事件链改为 N=2（2 个 participant ⇒ 2 条 `module.proposal`）。

### C5 `SdCommandDrain`（跨模块效果，app）

- 新文件：`app/sd/SdCommandDrain`；只经 `CoreSimos.submit`（`AppWritePathGuardTest` 仍绿）。
- 判据：跨模块效果 ⇒ **真 revision**；**幂等**（重复 drain 不重复提交）；被拒 ⇒ 无半写 revision、unit 未改、sd 侧 effect 仍 `FIRED`（**中间态可见**，且下次 drain 会重试）。
- 测试 2 条（`SdCommandDrainTest`）；`Shell.advanceAndDrain(...)` 即"提交成功后调 drain"的兑现。

### C6 Combat 端到端（条件驱动 + 冻结）

- `SdCombatEndToEndTest`：真 `SdCodec`+真 `SdTimeParticipant`+真 store，全部经 `CoreSimos.submit`。
- 判据：`AtOrAfterTick` 到点前/后 `currentStage` 逐值不同；同一坐标两次 `Replay` 逐字段相同、两次编码逐字节相同；恰好一个结局（重复 ⇒ 拒、不留 revision）。测试 1 条。

## 三 执行期取代说明

1. **C1-a（CombatState 的创建路径）**：spec §三.3 的 `CombatState.currentStage` 必须落在阶段链里，而 `sd.CreateCombat`（`combatId,name,participants`）在阶段表为空时无法给合法初始值。⇒ `sd.CreateCombat` 只建 `Combat`；**首个 `sd.AddCombatStage` 同时建 `CombatState`**，故其载荷在首阶段额外要求 `combatStateId` 与 `hex`（后续阶段可省）。spec/计划正文未改。
2. **G6（drain 幂等键）已裁（执行期）**：`EnqueueUnitCommand` 不增字段；幂等键 = `"drain:" + effectId`（R6 保证一个效果只 `FIRED` 一次 ⇒ effectId 稳定）；"已 drain" 由**时间线已提交 revision 行的 `commandId`**判定 ⇒ 进程重启/重放后仍成立。被拒的指令不写行 ⇒ 下次 drain 重试（选择重试而非静默丢弃）。
3. **sd 切片对"能推进的世界"是必需的**：`TimeAdvance` ④Validate 要求提案 namespace 在 base 里有切片；SdTimeParticipant 一注册，缺 sd 切片的 base 会让推进被拒。⇒ `DemoWorld.state` 与 9 个 app 测试夹具补入 `SdSnapshot(.., SdState.empty())`（+ `SdCodec`）。这是**语义正确的连带**，不是为过门禁。
4. **`Shell.advanceAndDrain` 是"提交后 drain"的落点**：`CoreSimos` 是 final、直接注入给工具/GUI，且 C5 明令**不改 Core、不引入模块钩子**（C24）⇒ 自动 drain 由 app 侧显式入口承担；**MCP/GUI 的 advance 未自动串 drain**（列为开口项，自然收口点是 D 阶段 `AdjudicatorRunner`）。
5. **`TriggerEvaluator` 的 v1 语义**：`ThresholdKills` = 全体 `LossRecord` 人员损失绝对值之和（sd 无独立击杀计数）；`AfterTicks(n)` 相对参照点（效果用 `createdTick`，阶段用 0）。仅 `AtOrAfterTick`/`ThresholdKills` 有判据（计划所要求的两类）。
6. **计划 §三 C1~C6 的用例推演数（+44）与实测（+33）有偏差**：以实测为准；判据条目均已覆盖，未凑数。

## 四 变异（九道门禁，装置 `.superpowers/sdd/2026-09-20-sd-simos/mutants/mut-run.py` + `mut-run.log`）

每轮：**先自证** orig/mutant/pushed/restored **四处 md5**（原≠变异、推入=变异、还原=原）；原地覆盖 canonical 文件；`clean test`（清陈旧 `.class`）；
断言 `COMPILATION ERROR`=0 且 `Tests run ≥ 1`；判红点落**被保护断言**；`cp` 逐字节还原；日志自指。

**15 个变异体：14 KILLED / 1 等值存活（补判据后转杀）**：

| id | 靶 | 红点 | 结论 |
|---|---|---|---|
| c1m1 | `CombatStages.append` 链校验 | `addStageRejectsBrokenChain` | KILLED |
| c1m3 | `CreateCombatHandler` 单位存在性 | `createCombatRejectsDanglingParticipant` | KILLED |
| c1m4b | `SetOutcomeTableHandler` 不换表 | `setOutcomeTableReplacesWeights` | KILLED |
| c2m1 | `CommitOutcomeHandler` 表内校验 | `commitOutcomeRejectsOutcomeFromAnotherStage` | KILLED |
| c2m2 | 已选过不覆盖 | `commitOutcomeRejectsSecondSelection` | KILLED |
| c3m1 | 上界校验 | `recordCasualtiesRejectsPersonnelOverBound` | KILLED |
| c3m2 | 上界差一 | `recordCasualtiesAcceptsExactPersonnelBound` | KILLED |
| c3m3 | `atRevision` | `recordCasualtiesStoresDeltaWithRevision` | KILLED |
| c4m1 | 条件未达成即产生 | `effectDoesNotFireBeforeTrigger` | KILLED |
| c4m3 | 阶段推进时间硬编码 | `stageAdvancesWhenAtOrAfterTickExitIsSatisfied` | KILLED |
| c4m4 | `Shell` 注册第二个 sd participant | `SimosToolsTest`（封存期抛） | KILLED |
| c5m1 | drain 幂等键 | `crossModuleEffectBecomesARealRevisionAndIsIdempotent` | KILLED |
| c5m3 | drain 不判 `FIRED` | 同上 | KILLED |
| c6m2 | `SdCodec.apply` 照抄 base 坐标 | `stageAdvanceIsConditionDrivenFrozenAndSelectsExactlyOne`（Core Validate ④） | KILLED |
| **c4m2** | 重复处理 `FIRED` 效果 | `effectDoesNotRepeatOnLaterTick` | **存活 — 等值变异体** |
| **c4m2b** | 同上（补 `firedPutInfoEffectDoesNotAppendAgain` 后） | 该新用例 | **KILLED** |

★ **c4m2 的存活根因（如实记）**：`EnqueueUnitCommand` 的 action 在"重复处理"下把 `Effect` 由 `FIRED` 再写成 `FIRED`，**状态无差异** ⇒ `SdChangeSet.between` 全 `Unchanged`，原用例判不出。
⇒ 补 `firedPutInfoEffectDoesNotAppendAgain`（`PutInfo` action 重复处理会**追加第二条 INFO**，状态有差异），同款变异体 `c4m2b` **红在该新用例**。**未为造红放松任何判据。**

## 五 我未能核实的（诚实清单）

- **`agentlib-mosire` 是外部依赖、不在本仓**：阶段 C **未触审批/传输路径**，但 D 阶段的 LLM/渠道会依赖它；本次**未核其 API**（沿用 spec §十四的盲区声明）。
- **`SdCommandDrain` 的自动触发**：MCP/GUI 的 `simos.advance` / `/api` 推进**未串 drain**（见取代说明 4）；C5/C6 的判据用 `Shell.advanceAndDrain` / 真 `CoreSimos` 显式调用。端到端"经 MCP 推进 ⇒ 自动 unit 战损"**未验**。
- **跨 revision 原子性**：drain 是"提交后再提交"，中间态**故意可见**（测试已断言），原子性仍为挂起项（spec §十二）。
- **`ThresholdKills` 是全局累计**（非 per-combat）：spec 的 `Trigger.ThresholdKills(int)` 无 combat 引用，本实现取全体损失之和；**真实多交战场景下的语义未验**。
- **`AfterTicks` / `UnitAtHex` / `OutcomeSelected` / `And` / `Or`** 的求值分支**无独立判据**（计划只要求 `AtOrAfterTick` 与 `ThresholdKills` 各一条）；`And`/`Or` 仅在 `TriggerEvaluator` 中实现、未单测。
- **`minDurationTicks`/`maxDurationTicks`** 只在 `CombatStage` 构造期校验、**推进逻辑未使用**（未实现 FATHM 时长上下限）。
- **C2 的"恰一个"端到端**只在**单 CombatState** 上验；`SdState` 允许多个 `CombatState` 指向同一 `Combat` 时 `SdCombats.stateOrNull` 取首个 ⇒ **多状态语义未定/未验**。
- **真档（19441 格）**与 **A\* 代价**：阶段 C 的夹具全是**合成小图**；`sd.A` 与真地图的交互未验。
- **前端**零改动（90/90）；阶段 C 无 JS 判据。
- **本机 nproc=8**，`clean verify` **48 s**（与 CLAUDE.md 记载的 nproc=2 机器完全不同）⇒ 并发/被杀轮结论**不适用于本机**，本报告只记本机实测。
- ★ **实测到的陈旧 `.class` 陷阱（本轮真中一次）**：变异装置 `clean test` 编译了变异体后 `cp` 还原**源文件**，但 `target/classes` 里仍是**变异体 .class**；随后的**非 `clean`** `mvn test` 复用了它 ⇒ 干净代码上跑出"红"（`firedPutInfoEffectDoesNotAppendAgain` 误判失败）。
  **判定"红"必须跑 `clean`，或核对 `.class` 与源文件 md5**——这正是 CLAUDE.md 形态 1 的"装置产物自带状态"。

## 六 提交

- 实现提交短 SHA：`（见提交信息 / progress.md 回填）`。
