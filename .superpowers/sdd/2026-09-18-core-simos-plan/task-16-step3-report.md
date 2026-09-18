# M4 Task 16 Step 3 报告：判据四端到端 —— `RealmEffectEndToEndTest`（R16）

日期：2026-09-19　分支：`m4/b16s3`（worktree `.claude/worktrees/b16s3`，基线 `da2178e`）
前置：Task 16 Step 1/2（`eaa8093`，`UnitTimeParticipant` + `RenameUnitHandler`）+ Task 13（`CoreSimos` 装配门面）。
task-only：**零 main 源码改动**（临时变异轮已逐字节还原，见 §四）。

## 〇 交付物

| 文件 | 内容 |
|---|---|
| `simos-core/src/test/java/io/mosire/simos/core/RealmEffectEndToEndTest.java` | 2 条用例：判据四 ①②（`realAdvanceMovesUnitAndReplayRebuildsItFromTheNewRevision`）+ R10 真实参与者（`proposalEventCarriesTheRealParticipantsReadWriteSets`） |
| `task-16-evidence/step3/mutants/{mut-round.sh,orig/UnitTimeParticipant.java,UnitTimeParticipant.m1.java,UnitTimeParticipant.m2.java}` | 移植自 task-14 的变异装置 + 原件快照 + 2 个变异体 |
| `task-16-evidence/step3/logs/{round-m1.log,round-m2.log}` | 两轮变异日志（含装置自指 md5 段） |
| `task-16-evidence/step3/{green-first-run.log,targeted-green.log,full-verify.log,md5-records.txt}` | 绿轮 / 定向 / 全量门禁 / md5 记录 |

## 一 判据四 ① / ② 的落点（逐值）

夹具（照 `SpiFixture`，`simos-core` 不能 import 领域测试类故就地复刻）：三格走廊 `[1,1]→[1,2]→[1,3]`，每段 1500 毫 MP；
单位 `u-1` 于 `T0` 出发、`speedAtDeparture=2`、`mobility=500` ⇒ 推进 `T0 → T0+2` 预算 4000、付清两段（3000）⇒ **ARRIVED 在 `[1,3]`**。
创世 checkpoint 含 `map` 与 `unit` **两个**切片（participant 要从 `state.module("map")` 取图）；真 codec = `MapCodec` + `UnitCodec`；真 participant = `UnitTimeParticipant(cost, "Map1")`。

| 项 | 断言 | 行 |
|---|---|---|
| **前提自证**（形态 1） | 推进前，**重放出的创世状态**里单位真有在途 `movement`（`contains(inFlight())`）、位置在 `[1,1]` | `RealmEffectEndToEndTest.java:129-133` |
| **①-a 位置真的变了** | 重放 `(main,2)` ⇒ `position.valueAt(T0+2)` 逐值 `contains([1,3])` | `:145-147` |
| **①-b movement 真的清了** | 同一重放 ⇒ `movement().isEmpty()`（逐值） | `:148` |
| **① 对照** | 起点段仍是 `[1,1]`（历史不改写，防"看起来动了"） | `:150` |
| **② 差分对拍** | `advanced`（重放 `(main,2)`）`isEqualTo(expected)`——期望状态**不经 DB、不经被测参与者**独立重建 | `:171` |
| **C28** | 单位切片 ref=`(main,2)`、timestamp=`T0+2` | `:175-176` |
| **map 未动（增量重放）** | map 切片 ref=`(main,1)`、timestamp=`T0`，且与创世切片 `equals` | `:179-181` |
| **重放稳定** | 第二次 `core.replay((main,2))` 与第一次 `equals` | `:184` |
| **R10（m2 咬点）** | 事件表 `simos.module.proposal` 的 `reads`/`writes` 逐字等于真实参与者声明的 canonical 列表 | `:220-224` |

`Committed (main,2)` 断言在 `:139-141`；`AdvanceTime` 的 `range=(T0, T0+2)`、N=4（⇒ `(main,2)` 不命中 checkpoint，重放真走"创世档 + 施变更集"）见夹具常量。

### 二 ② 的差分怎么算的（differential）

**不用 `UnitTimeParticipant.simulate` 当"期望"**（那是被测物自身，等于自证）。期望状态分两步独立重建：

1. 抵达点由 **M3 纯函数** `UnitMoves.evaluate(genesisUnit, T0+2, genesisMap, cost)` 算，并**额外断言** `status()==ARRIVED`、`currentHex()==[1,3]`、`nextHex()` 空——把算术前提钉死，避免期望建错时红点离题（`:156-163`）。
2. 重建规则照 **spec §9.1 手写**（`appendArrivalSegment`，`:262-281`）：往 `position` 追加 `Segment(T0+2, [1,3])`、`movement=Optional.empty()`、其余字段原样带过。
3. 外层状态用 `new StateMeta((main,2), T0+2)`；`map` 用创世切片原样（重放增量语义）；`unit` 用重建的 `UnitState`；`info=InMemoryInfoSystem.empty()`。

⇒ 差分只与 M3 纯函数、spec 规则、以及**重放要从创世 checkpoint 解码出的 base** 同源；**不含 participant 的实现路径**。方向性由 ① 的逐值断言兜底（m1 时 ① 先红）。

## 三 实测数字

### 3.1 全量门禁（权威证据：`step3/full-verify.log`）

命令 `./mvnw clean verify`（先 `./mvnw -q spotless:apply`，rc=0）。

| 项 | 实测 |
|---|---|
| rc / 结论 | **0 / `BUILD SUCCESS`** |
| 反应堆 | **6/6** SUCCESS（parent/util/map/social/unit/core） |
| 用例（util/map/social/unit/core） | **170 / 255 / 37 / 93 / 145 = 700**，Failures 0 / Errors 0 / Skipped 0 |
| SpotBugs | `BugInstance size is 0` × **5** |
| `[ERROR]` 行 | **0** |

★ **逐模块对差**（基线 698 = 170/255/37/93/143）：前四个模块一个都没动；core **143 → 145 恰 +2** = `RealmEffectEndToEndTest` 的 2 条用例。总 **698 → 700**。无别的模块差分。

### 3.2 定向（`targeted-green.log`）

`./mvnw -pl simos-unit,simos-core -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='UnitTimeParticipantTest,RenameUnitHandlerTest,RealmEffectEndToEndTest' test`
⇒ rc=0；unit 18（9+9）+ core 2 = 20，全绿。

## 四 变异自证（两轮，均杀；装置逐轮自指）

装置 `step3/mutants/mut-round.sh`（移植自 task-14）：干净世界 md5 门禁 → 变异体 ≠ 原件 → 白名单推成**目标类名** → **推入前删目标 `.class`** 逼 M4 重编 → 强制 `COMPILATION ERROR == 0` 且 `Tests run ≥ 1` → surefire 报告 mtime ≥ 本轮开跑 → 目标类 `.class` mtime ≥ 本轮开跑 → 期望类真红 → **`cp` 字节还原**（绝不 `git checkout --`）→ 三处 md5 追加进日志。

| 轮 | 变异 | 红在哪（照抄日志） | 红的理由是否被保护行为本身 |
|---|---|---|---|
| m1 | 已抵达的单位**不清 `movement`**（`arrived ? Optional.empty() : …` → 恒 `Optional.of(inFlight)`） | `realAdvanceMovesUnitAndReplayRebuildsItFromTheNewRevision:148 [① 已抵达 ⇒ movement 真的清了]`；`Tests run: 2, Failures: 1, Errors: 0` | **是**——红在判据四 ① 的那一行 |
| m2 | 参与者 **`writes` 恒为空集**（拿掉 `writes.add(unitAddress)`） | `proposalEventCarriesTheRealParticipantsReadWriteSets:224 [R10 在真实参与者上：writes 必须真的含 unit:u-1（m2 打的就是这条）]`；`Tests run: 2, Failures: 1, Errors: 0` | **是**——红在真实参与者 `writes` 的断言 |

md5（`step3/md5-records.txt`）：原件/快照 `485b94a5…`；m1 推送字节 `76744537…`、m2 推送字节 `e6806e0e…`；两轮还原后实测 `485b94a5…`（== 原件）。
两轮 `COMPILATION_ERROR_lines=0`、`Tests_run_lines=2`、报告 mtime 与目标 `.class` mtime 均落在各自轮内。

★★ **m2 是自证的，不是"未自证"**。计划原表的 m2 期望红在"R10 在真实参与者上的断言，**若本 Task 没落这条就如实记为未自证**"。本 Step 3 **落下了**这条：单参与者时 `writes` 为空**不会**让推进失败（无写-写冲突，①② 照样绿），只有读事件表查提案载荷的这条断言能咬住它——已实测（见上表）。task-16 Step 1/2 报告 §二 记的 m2 只到"参与者自己声明的 writes"（单元口径），本步把它闭合到**真管线写下的 `module.proposal` 事件**上。

## 五 取代说明 / 偏差（计划 vs 实测）

1. **`RealmEffectEndToEndTest` 用两条用例而非一条**：判据四 ①② 一条；R10 真实参与者（m2 咬点）一条。理由：m2 不会让 ①② 变色，混在一条里 m2 的红会落在整条用例上、读不出"红在 R10 那条断言"。
2. **差分的"独立"口径**：用 M3 `UnitMoves.evaluate` + spec §9.1 手写重建，**不调 participant**（见 §二）。计划给了"participant 提案 + apply"与"UnitMoves + spec 规则"二选一，选后者是为了斩断与被测物的同源。
3. **事件断言用 `EventStore.byCorrelation` 在 `core.close()` 之后读**（一棵独立 `SqliteStore`），与本仓既有的"关库后独立 store 读"口径一致。
4. **m2 变异体带一行注释**（`// m2 变异：writes.add(unitAddress); 被拿掉 …`）——与 Step 1/2 的原始变异体逐字节相同，未改。
5. **夹具复刻而非 import**：`simos-core` 的 test scope 看不见 `simos-unit` 的**测试类**（`SpiFixture` 是测试源，不在依赖 jar 里），故走廊 / 单位 / `inFlight` 就地复刻，与 `SpiFixture` 同值。

## 六 我未能核实的

1. **`CoreConfig.mapper` 仍无消费者**——与本任务无关，如实继承既有状态（其 Javadoc 自认）。
2. **多单位 / 父子编制下的提案**：夹具只有单单位；多单位读写集并集、子单位随父移动与在途行程的交互未落用例（Step 1/2 报告的同一挂账项，本步未扩展）。
3. **`NEED_REPLAN` / `IN_TRANSIT` 的端到端**：本步只走 ARRIVED 分支（判据四要求的正是它）；未在地图变化下走真管线的 `NEED_REPLAN`。
4. **裁定 39 的 `changeset_json` 线格式偏离**仍未收口（不在本步范围）；本步只证"写出的档能经真 `Replay` 读回"，未碰该列的最终形态。
5. **`R16` 的"推进结果与重放结果 equals"** 我解读为"重放 `(main,2)` == 独立期望状态"（并附逐值 ①②）；spec §十一 R16 原文的"原状态"指代略含混，若其本意是"重放 == 某次内存推进的中间态"，本步无该中间态可比（管线不对外暴露 ④ 的 applied 快照）——如实记。
6. **变异体的判别力只在 main 树形态下测过**（本 worktree 即 main 树形态，`.claude/worktrees` 路径形态）；未在"从模块目录起跑"形态复测（形态 1 的目录形态族）——本步的红来自**断言失败**（非扫描器空集），风险低于 R15 那类，但未逐一复测。

## 七 证据索引

- `step3/full-verify.log` —— 权威全量绿轮（rc=0、700、6/6、BugInstance ×5、ERROR 0）
- `step3/targeted-green.log` —— 定向 20 条
- `step3/green-first-run.log` —— `RealmEffectEndToEndTest` 首绿（修一次编译错误后，见 §八）
- `step3/logs/round-m1.log` / `round-m2.log` —— 两轮变异（含自指 md5 段）
- `step3/md5-records.txt` —— 原件/变异体/还原后 md5
- `step3/mutants/` —— 装置 + 原件快照 + 2 变异体

## 八 首次编译失败（保完整痕迹）

首次 `test` 编译失败一次：`unitOf` 助手返回 `UnitSnapshot` 而被赋给 `Unit`（2 处）。当轮改为返回 `Unit` 后即绿——§七 的 `green-first-run.log` 是修复**之后**的绿轮（与 task-16 Step 1/2 报告 §三 ⑦ 同类，如实留痕）。
