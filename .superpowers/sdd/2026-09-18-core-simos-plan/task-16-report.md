# Task 16 报告：unit 侧最小真实链路（Step 1 / Step 2）

日期：2026-09-18　分支：`m4/b16`（worktree `.claude/worktrees/b16`，基线 `ed60fd8`）
执行者：Task 16 实现者 agent

## 〇 交付物清单

**main 源码**（均在 `simos-unit/src/main/java/io/mosire/simos/unit/spi/`）：

| 文件 | 内容 |
|---|---|
| `UnitTimeParticipant.java` | Step 1：`namespace() == "unit"`；对持有在途 `Movement` 的单位用 `UnitMoves.evaluate` 算到 `range.to`；ARRIVED ⇒ position 段写抵达点 + 清空 movement；IN_TRANSIT / NEED_REPLAN ⇒ position 段写当前格 + 不改路线；无在途 ⇒ 不进变更集 |
| `RenameUnitHandler.java` | Step 2：`type() == "unit.RenameUnit"`；自己反序列化 payload（`{"id":…,"name":…}`）、调 `UnitOperations.rename`、返回 `HandlerOutcome.Applied(UnitChangeSet)` / `Rejected` |
| `UnitSnapshots.java` | 本包两个 SPI 类共用的「取 unit 切片」助手（装配故障 ⇒ `IllegalStateException`，与 `MapResolver` 同口径）。★ 计划外的第 3 个文件，见 §三 取代说明 ⑤ |

**测试**（`simos-unit/src/test/java/io/mosire/simos/unit/spi/`）：
`UnitTimeParticipantTest.java`（9 条）、`RenameUnitHandlerTest.java`（9 条）、`SpiFixture.java`（共用夹具）。

**证据**：`task-16-evidence/`（绿轮 ×2、变异轮 ×2、md5 记录、`orig/` + `mutants/`）。

## 一 实测结论行（照抄日志）

命令（两轮绿均为同一命令；★ 派单命令带 `-q`，实测为留全量证据去了 `-q`，判据等价，见 §三 ⑥）：

```
./mvnw -pl simos-unit -am -Dsurefire.failIfNoSpecifiedTests=false -Dtest='UnitTimeParticipantTest,RenameUnitHandlerTest' test
```

**首绿**（`green-first-run.log`，15:50）：

```
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.522 s -- in io.mosire.simos.unit.spi.RenameUnitHandlerTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.706 s -- in io.mosire.simos.unit.spi.UnitTimeParticipantTest
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
rc=0
```

**终绿**（`green-final-run.log`，15:57，m2 变异轮恢复原件后重跑，md5 复核 == 原件）：

```
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
rc=0
```

18 条 = UnitTimeParticipantTest 9 + RenameUnitHandlerTest 9。

## 二 变异自证（两轮全红、红点逐条核对、每轮 `grep -c "COMPILATION ERROR"` == 0）

装置口径：变异体按**白名单推成目标类名** `UnitTimeParticipant.java`（不是按变异文件名拷入）；每轮推入前/恢复后**清掉旧 `.class`** 再编；推入时断言落盘 md5 == 变异体且 != 原件，恢复后断言 == 原件。md5 全记录在 `task-16-evidence/md5-rounds.txt`。

| 轮 | 变异 | 红在哪（照抄日志） | 红的理由是否是被保护行为本身 |
|---|---|---|---|
| m1 | 已抵达的单位**不清 `movement`**（`arrived ? Optional.empty() : …` → 恒 `Optional.of(inFlight)`） | `arrivedUnitGetsArrivalPositionAndClearsMovement:92 [已抵达 ⇒ movement 真的清了] Expecting an empty Optional but was containing value: Movement[route=…]`；`Tests run: 18, Failures: 1, Errors: 0` | **是**——断言失败的正是"movement 真的清了"这一行 |
| m2 | 参与者的 **`writes` 恒为空集**（拿掉 `writes.add(unitAddress)`） | `readsCoverUnitAndRouteHexesWritesCoverUnit:133 Expecting actual: [] to contain exactly: ["unit:u-1"]`；`Tests run: 18, Failures: 1, Errors: 0` | **是**——红在真实参与者 `writes` 声明的那条断言 |

m2 的**诚实口径**：计划原表期望红在"R10 在真实参与者上的断言"。本 Task 落的这条真实参与者断言是 `readsCoverUnitAndRouteHexesWritesCoverUnit`（判据 ④ 之外的第二重价值：读写集在真实参与者上跑通一次），**但它断言的是参与者自己声明的 writes，不是 Resolve 消费读写集的端到端**——那要等 `CoreSimos`（Task 12/13）才有。两轮各只红 1 条、其余 17 条绿，红点均与被保护行为一一对应。

**Step 3 未做**，理由：`RealmEffectEndToEndTest`（判据四端到端）依赖 `CoreSimos`（Task 13，裁定 20：必须留到 13 之后单独补）。本 Task 只做 Step 1 + Step 2 + Step 4 里针对这两个类的变异自证。

## 三 取代说明（计划/派单函 vs 实测，以实测为准）

1. **`mapId` 由装配注入**（构造器 `UnitTimeParticipant(MovementCost cost, String mapId)`）。计划/spec 只写了"`MovementCost` 由装配注入"，读写集却要求 `map:<mapId>:hex.q_r` 形式——而 `GameMap` **没有 id 字段**（M2/M3 挂起项，spec §十二 第 11 条自认"mapId 只回显不可校验"），状态里导不出 mapId。照"装配注入无法从状态导出的事实"的既有口径补了第二个注入点；地图有了身份字段后，这里就是收紧点（Javadoc 已写明）。
2. **`range.to` 缺省（无上界推进）⇒ 提案零变更、不询价、不抛**。台账裁定 19 要求该边界必须显式决定：spec §5.4 第 0 项 Core 必拒无上界推进，参与者无可评估的时刻，交空提案让 Core 走自己的拒绝路径（真实链路里 Propose 先于 Validate，参与者**会**收到无上界区间）。用例 `unboundedRangeProposesZeroChange` 钉住（含"根本不询价"断言）。
3. **`range.to` 早于某单位 `departedAt` ⇒ 照调 `UnitMoves.evaluate` 让它抛**（裁定 19 的另一分支）：M3 对该输入的口径就是 IAE（调用方 bug），真实链路 `to` 严格晚于快照时刻，到不了这里。未单独落用例（该行为属 `UnitMoves`，M3 已测）。
4. **`RenameUnitHandler` 的拒绝面**：载荷不是合法 JSON / `id`·`name` 缺失或非字符串 / 查无此人 / 名字空白 ⇒ 一律 `Rejected`（理由进 `simos.command.rejected` 事件）。计划未指明这些分支的归置；照"载荷坏是命令的错、域规则违反折算在命令边界"落。同名改名为 `Applied` + 空变更集（`isEmpty()==true`，有用例钉住）。
5. **多出一个计划外文件 `UnitSnapshots.java`**：两个 SPI 类都要"从 state 取 unit 切片、装配故障当场炸"，抽成包私有助手避免两份拷贝。65 行。
6. **绿轮命令去了 `-q`**：派单命令是 `./mvnw -q …`，实测为在日志里留下 `Tests run` 汇总行（`-q` 会吞掉它）去掉 `-q`，命令的其余部分逐字一致。
7. **首次编译失败一次**（`hexAddress` 漏传 `mapId` 实参），当轮修复后即绿——记入以保"我验过了"的完整痕迹（`green-first-run.log` 是修复后的绿轮）。

## 四 判据对照（本 Task 能核的部分）

- Step 1 产出三行（抵达 / 在途 / 无在途）：`arrivedUnitGetsArrivalPositionAndClearsMovement`、`inTransitUnitGetsCurrentHexAndKeepsRoute`、`impassableEdgeKeepsRouteWithPositionAtCurrentHex`、`unitWithoutMovementDoesNotEnterChangeSet` 逐值断言，全部走 `UnitChangeSet.apply` 落回 base 后核值（不是只看提案对象）。
- 读写集：`readsCoverUnitAndRouteHexesWritesCoverUnit` 逐字钉 canonical 串（`unit:u-1`、`map:Map1:hex.1_1|1_2|1_3`、`writes=[unit:u-1]`），并复核每个串 `Address.parse(canonical)` 往返成立。
- 形态 4（纯转发/原样转交）：`evaluateReceivesStateMapAndRouteVerbatim`（成本桩记账：state 里的 `GameMap` **同一实例**被逐次转交、询价格子对 = 路线相邻对、按 path 序）；`payloadNameIsUsedVerbatim`（含空格/冒号的名字逐字保留）。
- 装配故障：`missingSlicesAreAssemblyFaults` / `missingUnitSliceIsAssemblyFaultNotRejection`（缺切片 ⇒ `IllegalStateException`，不走拒绝路径）。

## 五 我未能核实的

1. **SpotBugs / Checkstyle / Spotless 门禁未跑**——`mvn test` 不触发它们，按纪律关账 `clean verify` 由控制器统一跑（源码已过一次 `spotless:apply`，rc=0，但 SpotBugs 对新类的判定未核）。
2. **`simos-core` / Timeline / Resolve 的衔接**（Task 12/13 未完成）：提案的消费方、`WorldChangeSet` 汇总、读写集冲突判定都没在真实 Core 上跑过；本 Task 的 `TimeProposal` 只在单元口径下成立。
3. **R16 / 判据四端到端**（Step 3）——如 §二 所述未做，等 Task 13。
4. **m2 的"R10 经 Resolve"口径**——本 Task 只自证到"参与者自己声明的 writes"，Resolve 侧未核（同上第 2 条）。
5. **多单位 / 父子编制下的提案**：夹具只有单单位。多单位读写的并集、子单位随父移动（`effectivePosition`）与在途行程的交互，本 Task 未落用例。
6. **`position` 段与既有段同刻的冲突**（在 `range.to` 已有 position 段的输入）：依赖 `SegmentedSeries` 的严格升序校验兜底（会抛 IAE），未单独落用例。
