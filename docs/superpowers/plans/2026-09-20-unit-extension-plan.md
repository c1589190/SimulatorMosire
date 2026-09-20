# Unit 扩容计划 —— 编制两级 / 回归路径 / 三态 / 战损增量（bite-sized）

> 配套 spec：`docs/superpowers/specs/2026-09-20-unit-extension-design.md`（**判据见其 §八（20 条）**；`§〇.2` 的 P1~P14 **已全部裁定**）
> 边界 spec：`docs/superpowers/specs/2026-09-20-sd-simos-design.md`（**其 §十 = 与 sd 的边界**；sd 本身不在本计划范围）
> 台账：`.superpowers/sdd/2026-09-20-unit-extension/progress.md`（裁定与结论）
> ★ **执行期纪律**：本计划的代码草图 / 路径 / 名字是**计划期产物**，与 `src` 分歧处以**源码为准**，分歧逐条记入台账 §四。
> ★ **门禁基线（2026-09-20 主树实测）**：`./mvnw clean verify` 绿 = **987** = `util 170 / map 362 / social 45 / unit 131 / core 169 / app 110`、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。**本计划每条"门禁不变 / 预期数字"都以这个为基准**（不写 977）。

## 〇 通则（每个任务都适用）

- **单任务 worktree**：`.claude/worktrees/uetN`（分支 `ue/tN`，`ue` = unit-extension；若控制器另定代号，全篇替换）。★ **派发前一律先 `git reset --hard` 到当时 HEAD**（陈旧基线陷阱，M8 裁定 25）。
- **一次只跑一个 Maven**（本机 `nproc` 小、本地推理网关与 Maven 抢核；已实测"Maven 与 agent 并存"会杀掉 agent）。
- 每任务必须：`./mvnw -q spotless:apply` → **定向测试** → **合并后主树 `./mvnw clean verify`**（关账前）；关账前**单独跑 `spotbugs:check`**（`mvn test` 不跑 SpotBugs）。
- 迭代只跑相关单条用例；`-pl <模块> -am` 会带 `-Dtest=` 到每个模块，加 `-Dsurefire.failIfNoSpecifiedTests=false`。
- **护栏必须自证**：每个新护栏配**故意违规**用例 + **≥2 轮变异**（九道门禁：①干净世界 ②变异体字节不同 ③按白名单推成目标类名 ④清陈旧 `.class` ⑤`grep -c "COMPILATION ERROR"`=0 ⑥surefire 报告 mtime 落本轮内 ⑦红点落**被保护的那行** ⑧`cp` 逐字节还原 ⑨日志自指 md5）。
- **同一文件被两任务改 ⇒ 必须串行，后关账者重跑前者的变异轮**（旧证据的对象已被改掉，不重跑 = 拿旧证据给新字节背书）。本计划**全部任务串行**（理由见 §二）。
- **不 `git add -A`**：只 `git add` **显式路径**；`.superpowers/**` 若被 ignore，按本机 `git check-ignore -v <路径>` 结果决定是否 `-f`（**按机器**，别照抄）。**不加 `Co-Authored-By`**。该推就推。
- 证据放 `.superpowers/sdd/2026-09-20-unit-extension/tN-evidence/`（**不许放仓根**）。
- **不跑 Maven 的是"写计划的我"**；执行者按上述命令正常跑。

---

## §一 目标与总判据

**目标**：把 spec 的**五大块**落成可发布能力，且**旧行为一字不变**（硬约束：无 `attached`/`offset` 的旧档 ⇒ 默认 `attached=true`/`offset=empty` ⇒ `effectivePosition` 与今天逐字节相同）：

1. **编制两级（E1/P1~P4/P9/P11）**：`command_chain`（谁向谁报告，可多属，扁平星形）+ `Formation`（严格树仍用既有 `Unit.parent` + 新增 `attached`/`offset`）。落点 `Unit` 新字段与 `UnitState.commandChains` 组件。
2. **回归路径（E2/P7/P8）**：拆分后有能力的单位额外创建一条**回归路径**；`rejoinTarget` 存引用、**每 tick 重规划**、**大编制移动时终点随动**（不冻结旧 hex）。
3. **三态（E3/P5/P6/P13）**：`UnitStatus{MOVING,RESTING,ENGAGED}`，速度因子 `1000/500/250`‰；`planRoute` 冻结出发速度（在途改状态**不回溯**）。
4. **战损增量（E4/N3/P14）**：`unit.ApplyCasualties` —— **人员 + 装备双轨 delta**、上界 `|Δ| ≤ 当前值`、**未知装备键拒绝**；绝对值落 revision ⇒ **时间线恢复成立**。
5. **命令与 SPI 装配**：12 条新命令 handler 全注册进 `Shell.java:193-213` 一带；`type` 形状构造期校验；`unit` namespace 恰一个 participant；catalog 含全部新 type。

**总判据（spec §八 20 条，逐条落点）**：

| spec §八 # | 判据 | 落点任务 |
|---|---|---|
| 1 多属 / 2 无环 / 3 attached⊕offset / 4 detached 不回退 | 编制 | T1（3/4）、T4（2）、T5（1） |
| 5 同格合体 / 6 子树迁移整体性 | 编制 | T4 |
| 7 稀疏路点 / 8 回归随动 | 回归 | T6 / T7 |
| 9 三态速度 / 10 正交 / 11 不回溯 | 三态 | T2 |
| 12 delta / 13 上界 / 14 双轨 / 15 恢复 / 16 无明文泄漏 | 战损 | T8 |
| 17 往返 + Architecture 计数仍 4 | 地基 | T1 |
| 18 注册/形状/恰一 participant、19 `CommandChainId` 作 Map 键 | SPI/codec | T9 / T1·T5 |
| 20 sd 边界（经 drain） | —— | **超出本计划**（sd 未实现；归 sd 里程碑，见 §六 R7） |

★ **门禁不变口径**：除 `simos-unit`（T1~T8 加用例 ⇒ 131 → 131+N）与 `simos-app`（T9 加用例 ⇒ 110 → 110+M）与 `simos-core`（T8 的时间线恢复用例 ⇒ 169 → 169+K）外，`util 170 / map 362 / social 45` **必须逐值不变**；前端 `88/88` **必须不变**（本计划零前端改动）。关账时逐模块对差并记录 N/M/K。

---

## §二 任务总表（依赖与串并行）

| 任务 | 目标 | 依赖 | 主改文件（共享热点） | 并行性 |
|---|---|---|---|---|
| **T1** | 模型地基：新类型 + `Unit` 四字段 + `UnitState.commandChains` + `effectivePosition` 取代/共存 + 变更集/codec | 无（**Block 所有**） | `Unit.java` `UnitState.java` `UnitChangeSet.java` `UnitCodec.java` `UnitOperations.java` `UnitTimeParticipant.java` `CreateUnitHandler.java` | 无 |
| **T2** | 三态速度（factor / effectiveSpeed / planRoute 冻结 / SetStatus / CreateUnit 默认） | T1 | `UnitStatus.java` `Unit.java` `UnitOperations.java` `CreateUnitHandler.java` | 串行 |
| **T3** | 编制命令 A（attach 级联 / detach 只节点 / offset） | T1 | `UnitOperations.java` + 3 新 handler | 串行 |
| **T4** | 编制命令 B（子树迁移 + 拆合：同格 + MOVING 前置） | T2, T3 | `UnitOperations.java` + 3 新 handler | 串行 |
| **T5** | `command_chain` 命令（Create/Update + 多属 + Map 键往返 + disband 与链） | T1 | `UnitOperations.java` `UnitState.java` + 2 新 handler | 串行 |
| **T6** | 稀疏路线 `unit.PlanSparseRoute`（A\* 逐段展开；不可达命令期拒） | T1 | `UnitOperations.java` + 1 新 handler | 串行 |
| **T7** | 回归路径（`SetRejoinTarget` + participant 每 tick 重规划） | T2 | `UnitOperations.java` `UnitTimeParticipant.java` + 1 新 handler | 串行 |
| **T8** | 战损增量 `unit.ApplyCasualties`（双轨 delta + 上界 + 未知键拒 + 恢复） | T1 | `UnitOperations.java` + 1 新 handler + core 恢复用例 | 串行 |
| **T9** | SPI 装配（`Shell.java:193-213` 一带注册 12 handler + cost 注入 + catalog） | T2~T8 | `Shell.java` + app 测试 | 串行（依赖全部） |
| **T10** | 端到端判据 + 关账（20 条逐条实测值 + 变异汇总 + 未核实清单） | 全部 | 新增 e2e 测试 / 证据 / 报告 | 串行 |

```
T1 ─┬─→ T2 ─┬─→ T4 ─┐
    │       │        │
    ├─→ T3 ─┘        │
    ├─→ T5 ──────────┤
    ├─→ T6 ──────────┤
    ├─→ T7(←T2) ─────┤
    └─→ T8 ──────────┤
                     └─→ T9 ─→ T10
```

★★ **本计划全部任务串行**，理由是硬事实而非保守：**T1~T8 每一个都改 `UnitOperations.java`**（spec §一.5 要求新操作"照 `UnitOperations` 形制"落在同一操作面），T1/T2/T3/T5 还共享 `Unit.java`/`UnitState.java`；再叠加"**一次只跑一个 Maven**" ⇒ **任何两个任务并行都会让同一个文件有两个写者**（本项目最贵的教训形态）。故：
- T2~T8 的**逻辑依赖**（上表）只决定先后，**不**产生可并行对；
- 每一对前后任务若共享文件，**后关账者必须重跑前者的变异轮**（至少重跑与该文件相关的那些变异体），并把复现结果写进 `tN-evidence/`。

★ **唯一"看起来可并行"却证据不成立的是 T9 与 T10 之外的 app 测试文件**：T9 的 app 测试文件与 T1~T8 不相交，但 T9 **依赖它们全部关账** ⇒ 无实际并行窗口。

---

## §三 逐任务 bite-sized 步骤

> 每步 1~3 个工具动作、能独立验证。★ 表示 spec 缺口 / 待裁项（见 §六），执行者必须在台账记裁定。

### T1 模型地基（Block：所有下游）

**目标**：把 spec 需要的**全部新状态类型**与 `Unit`/`UnitState` 的**新形状一次落地**（编译耦合一次付清），且**旧行为一字不变**（`attached=true`、`offset=empty`、`status=MOVING`、`rejoinTarget=empty`、`commandChains=empty`）。★ **不把 `Unit` 的 record 组件分两次加**——加一次 record 组件会打断全仓约 40 处 `new Unit(...)`/`new UnitState(...)`，分次加 = 把同一笔代价付两遍（M9 T6 的 compile-coupling 教训）。

**依赖**：无。

**文件清单**

新增 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/UnitStatus.java`
- `simos-unit/src/main/java/io/mosire/simos/unit/RelativeOffset.java`
- `simos-unit/src/main/java/io/mosire/simos/unit/CommandChainId.java`
- `simos-unit/src/main/java/io/mosire/simos/unit/CommandChain.java`

修改 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/Unit.java`（+4 字段；兼容构造器）
- `simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java`（+`commandChains` 组件；`effectivePosition` 取代/共存）
- `simos-unit/src/main/java/io/mosire/simos/unit/change/UnitChangeSet.java`（+同名列组件）
- `simos-unit/src/main/java/io/mosire/simos/unit/codec/UnitCodec.java`（`CommandChainId` 键反序列化器）
- `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`（`copy`/`withUnit` 连带）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/CreateUnitHandler.java`（默认段）
- `simos-unit/src/main/java/io/mosire/simos/unit/move/UnitMoves.java`（**production 拷贝点**要保留新字段）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java`（`withPositionAndMovement` 保留新字段）

修改 test（兼容构造器让多数调用点**零改动**，只动必须动的）：
- `simos-unit/src/test/java/io/mosire/simos/unit/change/UnitRoundTripTest.java`（switch 登记 `commandChains`；`changeSetHasExactlyOneComponent` → 二组件断言）
- `simos-unit/src/test/java/io/mosire/simos/unit/UnitStateTest.java`（+attached/offset/detached 的 5 行表）
- `simos-unit/src/test/java/io/mosire/simos/unit/codec/UnitCodecTest.java`（+含非空 `commandChains` 的往返；`SegmentedSeries<Boolean>` 往返验证）
- `simos-unit/src/test/java/io/mosire/simos/unit/UnitTest.java`（新字段构造期校验）——**仅在实际需要时**改。

**bite-sized 步骤**

1. 新增 `UnitStatus`（纯 enum，三值 `MOVING/RESTING/ENGAGED`；**本任务不加 factor**）与 `RelativeOffset(int dq, int dr)`（record，无范围约束）。
   ⇒ 验证：`./mvnw -q -pl simos-unit -Dtest=UnitStatusTest,RelativeOffsetTest -Dsurefire.failIfNoSpecifiedTests=false test` 绿；两 record 的 `equals` 逐值。
2. 新增 `CommandChainId(String value)`（裸值 `toString` + `static parse` + 空白即抛，照 `UnitId.java`）与 `CommandChain(CommandChainId id, String name, UnitId commander, Set<UnitId> members)`（构造期：`commander ∈ members`、`members` 非空、`Set` 冻在赋值处走 `Collections.unmodifiableSet(LinkedHashSet)`）。
   ⇒ 验证：`CommandChainTest` 绿（`commander∉members` ⇒ 抛；`members` 空 ⇒ 抛；多属同一 unit 不抛）。
3. `Unit.java` 加 4 个 record 组件：`UnitStatus status`、`SegmentedSeries<Boolean> attached`、`SegmentedSeries<Optional<RelativeOffset>> offset`、`Optional<UnitId> rejoinTarget`；**加一个兼容构造器**（9 参旧签名 ⇒ 以 `parent.segments().get(0).from()` 为 anchor 造 `attached`/`offset` 的锚段，`status=MOVING`、`rejoinTarget=empty`）。
   ⇒ 验证：`UnitTest` 全绿（旧调用点零改动）；新构造器产生的 `attached.valueAt(任意 t)==true`。
4. `UnitState.java` 加组件 `Map<CommandChainId, CommandChain> commandChains` + 兼容构造器（1 参 ⇒ `Map.of()`）；构造期不变量：链 id 不重复（Map 天然）、`commander`/全 `members` 存在于 `units`、`commander ∈ members`；保序不可变（`LinkedHashMap` + `unmodifiableMap`，**绝不用 `Map.copyOf`**）。
   ⇒ 验证：`UnitStateTest` 绿（悬空引用 ⇒ 抛；`commandChains` 迭代序 == 插入序）。
5. `UnitState.effectivePosition` 按 spec §一.4 的 **5 行表**改：`attached.valueAt(at)` 为 true 且自身无位置 ⇒ 父位 ⊕ `offset.valueAt(at)`（present 时）否则父位；`attached=false` 且自身无位置 ⇒ **`Optional.empty()`（不回退父）**。
   ⇒ 验证：新增 5 条用例逐行钉（含"offset 为空 ⇒ 与今天逐字相同"的回归条）；★ 非零偏移夹具。
6. `UnitChangeSet` 加 `FieldDelta<CommandChain> commandChains` 组件；`between`/`apply`/`isEmpty` 同步；`UnitRoundTripTest` 的 `mutate`/`changedOf` `switch` 登记新组件名、把 `changeSetHasExactlyOneComponent` 改为"两边各 2 组件且同名"。
   ⇒ 验证：`UnitRoundTripTest` 全绿；`UnitChangeSetTest` 绿。
7. `UnitCodec.keyModule()` 注册 `CommandChainId` 键反序列化器；`UnitCodecTest` 加"含 2 条链 + 同一 unit 多属"的快照与变更集往返。
   ⇒ 验证：`UnitCodecTest` 绿。★ 若 `SegmentedSeries<Boolean>` 往返失败（spec §九未核实项）⇒ 走**退化分支**：`attached`/`offset` 改普通字段、`rejoinTarget` 保持普通字段，并把选择写进台账 §四（**此分支下 T2 的"在途不回溯"判据不受影响**）。
8. 连带更新 `UnitOperations.copy/withUnit`、`UnitMoves.evaluate` 的 `frozen` 拷贝、`UnitTimeParticipant.withPositionAndMovement`、`CreateUnitHandler` 的 `new Unit(...)` 一律走**新 canonical 形态**（不用兼容构造器，避免丢字段）；`UnitOperations.withUnit` 保留 `state.commandChains()`。
   ⇒ 验证：`./mvnw -q -pl simos-unit test` 全绿。
9. 全仓编译审计：`./mvnw -q -pl simos-app,simos-core -am -DskipTests test-compile`（确认 app/core 测试经兼容构造器零改动编过）。
   ⇒ 验证：`[ERROR]` 0 行。

**判据（可实测值）**
- 旧行为回归：既有 `UnitStateTest` / `UnitOperationsTest` / `UnitMovesTest` 全绿（`attached=true`+`offset=empty` ⇒ `effectivePosition` 与 M3 逐值相同）。
- `attached=true`、无自身位置、`offset=(2,-1)` ⇒ 有效位置 = 父位 ⊕ (2,-1)（**不等于**父位）。
- `attached=false`、无自身位置 ⇒ 空（**不回退**父位）。
- 同一 unit 在 2 条链：往返后两条都在。
- `UnitRoundTripTest` 绿；`ArchitectureGuardsTest.changeSetHasExactlyFourMainSourceImplementors` **仍绿**（本次只在 `UnitChangeSet` 内加组件，实现者仍 4 个）。
- `simos-unit` 计数 = **131 + N**；`util/map/social` 逐值不变；前端 88/88 不变。

**变异思路（≥2 轮）**
- **m1**：`effectivePosition` 删掉 offset 加项（非零偏移夹具）⇒ "父位⊕offset" 用例红。
- **m2**：`effectivePosition` 删掉 detached 分支（回退父位）⇒ "detached ⇒ 空" 红。
- **m3**：`UnitState` 构造不查 `commander`/`members` 存在 ⇒ "悬空引用 ⇒ 抛" 红。
- **m4（护栏自证）**：`UnitRoundTripTest.mutate` 不登记 `commandChains` ⇒ 该用例红（证明铁律 5 的反射枚举真的会响）。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t1-evidence/`（定向 surefire 报告 + `clean verify` 主树日志 + 4 个变异体的 `orig_md5/mutant_md5/红点` 自记 + 台账 §四 的退化分支裁定记录）。

**提交信息样式**
```
unit-ext T1：模型地基（UnitStatus/RelativeOffset/CommandChain + Unit 四字段 + UnitState.commandChains + 往返）
- <要点逐条>；干净轮 simos-unit N 条；变异 m1~m4 全 KILLED
- 门禁：clean verify rc=0、<总数> = 170/362/45/<131+N>/169/110、BugInstance 0×6、ERROR 0、前端 88/88
```

---

### T2 三态速度（E3 / P5 / P6 / P13）

**目标**：三态 → 速度（`1000/500/250`‰），`planRoute` 冻结 `effectiveSpeed`，`unit.SetStatus`，`CreateUnit.status` 默认 `MOVING`。

**依赖**：T1。

**文件清单**

新增 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/SetStatusHandler.java`

修改 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/UnitStatus.java`（`factorPerMille()`）
- `simos-unit/src/main/java/io/mosire/simos/unit/Unit.java`（`effectiveSpeed()`）
- `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`（`setStatus`；`planRoute` 用 `effectiveSpeed`）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/CreateUnitHandler.java`（可选 `status`，缺省 `MOVING`）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitPayloads.java`（`optionalStatus` 解析）

新增 test：
- `simos-unit/src/test/java/io/mosire/simos/unit/UnitStatusTest.java`
- `simos-unit/src/test/java/io/mosire/simos/unit/spi/SetStatusHandlerTest.java`

修改 test：`simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`、`simos-unit/src/test/java/io/mosire/simos/unit/move/UnitMovesTest.java`

**bite-sized 步骤**

1. `UnitStatus.factorPerMille()`：`MOVING 1000`、`RESTING 500`、`ENGAGED 250`；`UnitStatusTest` 逐值钉 + "三值互不相等且 `RESTING/ENGAGED < MOVING`"。
   ⇒ 验证：定向绿。
2. `Unit.effectiveSpeed()` = `speed × factorPerMille(status) / 1000`。★ **缺口 U1（待裁）**：该式可能 < 1（`speed=2`、`ENGAGED ⇒ 0`），与 `Movement.speedAtDeparture ≥ 1` 冲突；**建议** `Math.max(1, Math.floorDiv(speed*factor + 500, 1000))`（与 `TerrainMovementCost.scale` 同舍入口径），并要求判据夹具用 `speed ≥ 4` 保证三档可区分。执行者在台账记本裁定。
   ⇒ 验证：`UnitTest` 加 `speed=8 ⇒ 8/4/2`；`speed=2 ⇒ max(1,·) 不会 < 1`（若采纳 clamp）。
3. `UnitOperations.planRoute` 把 `unit.speed()` 换成 `unit.effectiveSpeed()` 冻进 `Movement.speedAtDeparture`（**只改这一处**，`Movement`/`UnitMoves` 的冻结规则不动）。
   ⇒ 验证：同单位同路线，`MOVING/RESTING/ENGAGED` 三次 `speedAtDeparture` = `8/4/2`（可区分且有序）。
4. `UnitOperations.setStatus(state, id, status)` + `SetStatusHandler`（payload `id,status`；未知串 ⇒ 拒；`UnitStatus.valueOf` 失败 ⇒ 拒）。
   ⇒ 验证：`SetStatusHandlerTest` happy path + 未知状态拒绝。
5. `CreateUnitHandler` 读**可选** `status`（缺省 `MOVING`）；`UnitPayloads.optionalStatus`。
   ⇒ 验证：不传 `status` ⇒ `MOVING`；传 `"RESTING"` ⇒ `RESTING`；传 `"X"` ⇒ 拒。
6. 在途不回溯用例：出发（MOVING）→ 走 1 tick → `SetStatus(RESTING)` ⇒ 已走路程与 `speedAtDeparture` **不变**。
   ⇒ 验证：该断言绿。
7. 正交性用例：`UnitMoves.evaluate` 的 `MovementStatus` 与 `UnitStatus` 互不干扰（`MOVING` 单位带 Movement 仍可 `IN_TRANSIT/ARRIVED/NEED_REPLAN`）。
   ⇒ 验证：`UnitMovesTest` 新增条绿；`MovementStatus` 源码零改动。

**判据（可实测值）**
- `factorPerMille`：`1000 / 500 / 250`（逐值）。
- `speed=8`：`effectiveSpeed` = `8 / 4 / 2`。
- 同路线三次 `planRoute` 的 `speedAtDeparture` = `8 / 4 / 2`（**这是 m1/m2 的靶子**）。
- 在途 `SetStatus` 后 `speedAtDeparture` 不变、已走路程不变。
- `unit.SetStatus` 未知 status ⇒ `Rejected`。
- `CreateUnit` 缺省 `status == MOVING`。
- `simos-unit` 计数 = `131 + N(T1) + N(T2)`；前端 88/88 不变。

**变异思路（≥2 轮）**
- **m1**：`factorPerMille` 三档都返回 `1000` ⇒ 三档速度不可区分 ⇒ 红。
- **m2**：`planRoute` 用 `unit.speed()`（即恒 MOVING 口径）⇒ RESTING/ENGAGED 出发速度 = MOVING ⇒ 红。
- **m3**：`setStatus` 实时改写 `Movement.speedAtDeparture` ⇒ "已走路程不变"红。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t2-evidence/`。

**提交信息样式**：`unit-ext T2：三态速度（1000/500/250‰ + planRoute 冻结 + SetStatus；缺口 U1 的 clamp 裁定在案）`

---

### T3 编制命令 A —— attach 级联 / detach 只节点 / offset（E1 / P1 / P2 / P3）

**目标**：`attached`/`offset` 的命令面；`AttachUnit` 级联、`DetachUnit` 只节点、`SetFormationOffset`。

**依赖**：T1。

**文件清单**

新增 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/AttachUnitHandler.java`（`unit.AttachUnit`：`id, parent`）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/DetachUnitHandler.java`（`unit.DetachUnit`：`id`）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/SetFormationOffsetHandler.java`（`unit.SetFormationOffset`：`id, dq?, dr?`，两者都 null ⇒ 清偏移）

修改 main：`simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`（`attachSubtree` / `detachUnit` / `setOffset`）

修改 test：`simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java`、`simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`、`simos-unit/src/test/java/io/mosire/simos/unit/spi/SpiFixture.java`（若需真三层夹具）

**bite-sized 步骤**

1. `attachSubtree(state, id, parent, at)`：设 `id.parent=parent`（追加段）并把 `id` **及其全部后代** 的 `attached` 追加 `true` 段（P3：attach 级联）；`parent` 落在 `id` 子树内 ⇒ **op 内先显式拒**（环，给可读理由）。
   ⇒ 验证：三层树 `r→a→b`，attach `a` 到新父 ⇒ `a` 与 `b` 的 `attached.valueAt(at)` 均 true。
2. `detachUnit(state, id, at)`：**只**给 `id` 追加 `attached=false` 段；子节点**不动**（P3 刻意不对称；`id` 已是根 ⇒ 拒）。
   ⇒ 验证：detach `a` ⇒ `a` false 而 `b` 仍 true。
3. `setOffset(state, id, offset, at)`：追加 `offset` 段；`id` 不存在 ⇒ 拒（`offset` 形状由 payload 层判；**不强制落图内**，P2）。
   ⇒ 验证：设偏移后 `attached=true`+无自身位置 ⇒ `effectivePosition` = 父位⊕偏移；清偏移 ⇒ 回父位。
4. 三个 handler 照 `ReparentUnitHandler` 形制（`state.meta().timestamp()` 追加段、`between` 产出、`IllegalArgumentException` 折 `Rejected`）。
   ⇒ 验证：`UnitCommandHandlersTest` 逐条 happy/拒绝。
5. `UnitPayloads` 复用 `optionalId`/`optionalText`/`requireInt`；`SetFormationOffset` 的 `dq`/`dr` 用 `requireInt`，允许其中任一缺失 = 不清（两者皆缺 ⇒ 清）。
   ⇒ 验证：畸形载荷折拒绝用例。

**判据（可实测值）**
- attach 级联：`a`/`b` 都 true；detach 只节点：`a` false、`b` true（**逐值、逐 id**）。
- `attached=true`+无自身位置+`offset=(1,0)` ⇒ 有效位置 = 父位⊕(1,0)。
- `detached`+无自身位置 ⇒ 空。
- attach 成环 ⇒ 拒且状态不变；目标不存在 ⇒ 拒。
- `simos-unit` 计数累加；前端 88/88 不变。

**变异思路（≥2 轮）**
- **m1**：detach 改成级联 ⇒ "子节点不动"红。
- **m2**：attach 不级联（只节点）⇒ "后代 attached=true"红。
- **m3**：删环校验 ⇒ 成环通过 ⇒ 红。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t3-evidence/`。

**提交信息样式**：`unit-ext T3：编制命令 A（attach 级联 / detach 只节点 / SetFormationOffset）`

---

### T4 编制命令 B —— 子树迁移 + 拆合（E2 / P4 / P9）

**目标**：`ReparentSubtree`（整棵子树）、`SplitFormation`/`MergeFormation`（拆合；**同格 + MOVING** 两个独立前置）。

**依赖**：T2（`MOVING`）、T3（`attach`/`detach` 复用）。

**文件清单**

新增 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/ReparentSubtreeHandler.java`（`unit.ReparentSubtree`：`rootId, parent?`）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/SplitFormationHandler.java`（`unit.SplitFormation`：`rootId, subUnitIds[]`）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/MergeFormationHandler.java`（`unit.MergeFormation`：`childId, parentId`）

修改 main：`simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`（`reparentSubtree` / `splitFormation` / `mergeFormation` + 子树/祖先遍历私有 helper）

修改 test：`simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java`、`simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`

**bite-sized 步骤**

1. `reparentSubtree(state, rootId, newParent, at)`：对 `rootId` **及其全部后代**在同 `at` 追加 `parent` 段（`newParent` 对 root；后代父不变——只有 root 换父）；`newParent` 落在被迁子树内 ⇒ **显式拒**（环）。
   ⇒ 验证：`r→a→b`，迁移 `a` 到 `c` ⇒ `a.parent=c`、`b.parent=a`；`c` 是 `b` 的后代 ⇒ 拒。
2. `splitFormation(state, rootId, subUnitIds, at)`：每个 `subUnitId` 必须**在 `rootId` 子树内**（否则拒）；然后 `detachUnit` 各自（节点级）。
   ⇒ 验证：目标不在子树 ⇒ 拒；在 ⇒ `attached=false`。
3. `mergeFormation(state, childId, parentId, at)`：**同格**（两者 `effectivePosition(at)` 都 present 且相等，否则拒）**且** `child.status == MOVING`（否则拒）；通过则 `child.parent=parentId` + `child.attached=true`（P9：不销毁节点）。
   ⇒ 验证：不同格 ⇒ 拒；同格但 RESTING/ENGAGED ⇒ 拒；同格+MOVING ⇒ 过。
4. 三个 handler；`SplitFormation` 的 `subUnitIds` 用 `UnitPayloads.requireWaypoints` 同款数组解析的**字符串数组**（新增 `requireTextArray` 到 `UnitPayloads`，若没有现成）。
   ⇒ 验证：逐条 happy/拒绝用例绿。
5. 端到端经命令的断言：三条命令都经 `between` 产变更集、往返绿。
   ⇒ 验证：`UnitCommandHandlersTest` 绿。

**判据（可实测值）**
- 子树迁移后**每个后代**的 `parent.valueAt(at)` 都对（逐 id 断言；只改 root 的变异会红）。
- 新父落在子树内 ⇒ 拒、状态不变。
- `SplitFormation` 目标不在 root 子树 ⇒ 拒。
- `MergeFormation`：不同格 ⇒ 拒；`status != MOVING` ⇒ 拒；同格 + MOVING ⇒ 过（且 `child.parent==parentId`、`attached==true`）。
- `simos-unit` 计数累加；前端 88/88 不变。

**变异思路（≥2 轮）**
- **m1**：`reparentSubtree` 只改 root、不改后代 ⇒ 后代父不变 ⇒ 红。
- **m2**：删环校验 ⇒ 通过 ⇒ 红。
- **m3**：删同格校验 ⇒ 不同格通过 ⇒ 红。
- **m4**：合并前置不查 `MOVING` ⇒ 非 MOVING 通过 ⇒ 红。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t4-evidence/`。

**提交信息样式**：`unit-ext T4：编制命令 B（ReparentSubtree 整树迁移 + Split/MergeFormation 同格+MOVING 前置）`

---

### T5 `command_chain` 命令（E1 / P11）

**目标**：`CreateCommandChain`/`UpdateCommandChain`；多属；`CommandChainId` 作 Map 键往返；`disband` 与链的交互。

**依赖**：T1（组件与类型已在）。

**文件清单**

新增 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/CreateCommandChainHandler.java`（`unit.CreateCommandChain`：`chainId, name, commander, members[]`）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/UpdateCommandChainHandler.java`（`unit.UpdateCommandChain`：`chainId, name?, commander?, members?`）

修改 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`（`createChain` / `updateChain`；`disband` 的链前置）
- `simos-unit/src/main/java/io/mosire/simos/unit/UnitState.java`（**仅当**链清理落此处；否则不动——**与 T1 串行**）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitPayloads.java`（`requireTextArray` / `optionalTextArray`）

修改 test：`simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java`、`simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`、`simos-unit/src/test/java/io/mosire/simos/unit/codec/UnitCodecTest.java`

**bite-sized 步骤**

1. `createChain(state, chain, at)`：重 id ⇒ 拒；成员不存在 ⇒ 拒；`commander∉members` ⇒ 拒（构造期也会拦，op 先给可读理由）；**多属不禁**（同一 unit 出现在多条链是正常态）。
   ⇒ 验证：同一 unit 属 2 条链 ⇒ 两条都在。
2. `updateChain(state, id, name?, commander?, members?)`：未给字段**不动**（不是清空）；给 `members` ⇒ `commander` 必须 ∈ 新 members（否则拒）。
   ⇒ 验证：只改 name ⇒ members 逐值不变。
3. 两个 handler + payload 解析（`members` 字符串数组）。
   ⇒ 验证：逐条 happy/拒绝。
4. `Codec` Map 键往返（T1 已注册键反序列化器）：含 2 条链且同一 unit 多属的 `UnitState` 快照 + `UnitChangeSet` 各往返一次。
   ⇒ 验证：`UnitCodecTest` 绿（★ m3 的靶子）。
5. ★ **缺口 U2（待裁）**：`disband` 删单位会让链引用悬空（违反 §一.2 不变量 2）。**建议**：`disband` **拒绝**当该单位仍是任何链的 commander/member（同既有"先改编下属再解散"口径：先修链）；执行者记台账。
   ⇒ 验证：链中单位被 `disband` ⇒ 拒（若采纳建议）。
6. 链**不构成层级**（扁平星形）⇒ 无需查环；显式写一条"多条链交叉不产生环"的用例。
   ⇒ 验证：该用例绿。

**判据（可实测值）**
- 多属：同一 unit 在 2 条链 ⇒ 两条都在、往返一致（**m1 靶子**）。
- 重 id ⇒ 拒；`commander∉members` ⇒ 拒；成员不存在 ⇒ 拒。
- `UpdateCommandChain` 未给字段不动（**m2 靶子**）。
- `CommandChainId` 作 `commandChains` 的 Map 键能往返（**m3 靶子**）。
- `disband` 与链的交互按裁定（建议拒绝）。
- `simos-unit` 计数累加；前端 88/88 不变。

**变异思路（≥2 轮）**
- **m1**：链做成单属（键改 `UnitId`）⇒ 第二条链写不进 ⇒ 红。
- **m2**：`updateChain` 整体替换 `members`（未给也清空）⇒ 红。
- **m3**：不注册 `CommandChainId` 键反序列化器 ⇒ 解码抛 ⇒ 红。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t5-evidence/`。

**提交信息样式**：`unit-ext T5：command_chain 命令（Create/Update + 多属 + Map 键往返 + disband 交互裁定）`

---

### T6 稀疏路线 `unit.PlanSparseRoute`（E2 / P12）

**目标**：稀疏 `waypoints` 经 A\* **逐段展开**成逐格 `path`；任一相邻段不可达 ⇒ **命令期拒绝**。

**依赖**：T1。

**文件清单**

新增 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/PlanSparseRouteHandler.java`（`unit.PlanSparseRoute`：`id, waypoints[]`）

修改 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`（`planSparseRoute` 或 `expandSparsePath` 纯函数 + 复用 `planRoute`）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitPayloads.java`（复用 `requireWaypoints`；无需改）

修改 test：`simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java`、`simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`

★ **装配注入（缺口 U3，待裁）**：`PlanSparseRouteHandler` 需要 `MovementCost` + `GameMap`。**建议**：构造器注入 `MovementCost`，`Shell` 传 `TerrainMovementCost.INSTANCE`（与 `UnitTimeParticipant` 同源）；`map` 从 `state.module("map")` 读（照 `UnitTimeParticipant.mapOf`）。执行者记台账。

**bite-sized 步骤**

1. 纯函数 `expandSparsePath(map, unit, waypoints, cost)`：对每对相邻 waypoint 调 `PathFinder.findPath(map, from, to, unit, cost)`（`PathFinder.java:59`）；任一段 `empty` ⇒ 抛（⇒ handler 折拒）；拼接时去掉每段的**重复首格**；拼成的 `path` 交给 `new Route(waypoints, expandedPath)` 做**全部构造期校验**（子序列/相邻/无重复）。
   ⇒ 验证：`(1,1)→(1,3)` 展开为 `[(1,1),(1,2),(1,3)]`、`Route` 通过。
2. handler：读 `state.module("map")`（装配故障当场炸，同 `UnitTimeParticipant`），起点校验沿用 `UnitOperations.planRoute`（起点 == `effectivePosition`），其余同 `PlanRouteHandler`。
   ⇒ 验证：不可达 ⇒ `Rejected`；起点不符 ⇒ `Rejected`。
3. 边界用例：`waypoints` 相邻（等价既有 `PlanRoute`）⇒ 展开后与既有逐字相同；`waypoints` 少于一格/重复 ⇒ 由 `Route` 拒。
   ⇒ 验证：`UnitCommandHandlersTest` 新增条绿。
4. ★ **风险 R4（§六）**：A\* 各段拼接可能产生 `Route` 禁止的重复格（跨段回头）或让 `waypoints` 子序列扫描错位 ⇒ 该情形应**命令期拒绝**（由 `Route` 构造期抛出、handler 折拒），并配一条"跨段重复 ⇒ 拒"的用例。
   ⇒ 验证：该拒绝用例绿。

**判据（可实测值）**
- 非相邻 `waypoints` ⇒ 得到逐格 `path`、`Route` 构造通过（**m1 靶子**）。
- 段不可达 ⇒ 命令期拒、`revisions` 行数不变（**m2 靶子**）。
- 起点 ≠ `effectivePosition` ⇒ 拒（**m3 靶子**）。
- 展开后的 `path` 满足 `Route` 全部约束（子序列/相邻/无重复）。
- `simos-unit` 计数累加；前端 88/88 不变。

**变异思路（≥2 轮）**
- **m1**：回到 `new Route(waypoints, waypoints)`（不展开）⇒ 非相邻输入被 `Route` 构造期拒 ⇒ 红。
- **m2**：段不可达时静默截断（不抛）⇒ "不可达 ⇒ 拒" 红。
- **m3**：删起点校验 ⇒ 红。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t6-evidence/`。

**提交信息样式**：`unit-ext T6：稀疏路线 unit.PlanSparseRoute（A* 逐段展开；不可达命令期拒；注入裁定 U3）`

---

### T7 回归路径（E2 / P7 / P8）

**目标**：`unit.SetRejoinTarget` + `UnitTimeParticipant` 每 tick 重规划；**大编制移动时终点随动**（不冻结旧 hex）。

**依赖**：T2（status）。

**文件清单**

新增 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/SetRejoinTargetHandler.java`（`unit.SetRejoinTarget`：`id, target?`；null 清）

修改 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`（`setRejoinTarget` + "有能力回归"判定 helper）
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/UnitTimeParticipant.java`（每 tick 重规划；**与 T1 串行**）
- `simos-unit/src/main/java/io/mosire/simos/unit/Unit.java`（`rejoinTarget` 字段已在 T1；**仅当退化分支**改普通字段）

修改 test：`simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitTimeParticipantTest.java`、`simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`

★★ **三处缺口（待裁，必须在动笔前记台账）**：
- **U4 存储形态**：spec §二.2 写 `rejoinTarget: UnitId`，但未说放哪。**建议**：`Unit.rejoinTarget: Optional<UnitId>` 普通字段（与 `status` 同族；历史由 revision 承载）。T1 已按此落。
- **U5 "状态允许移动"定义**：P7 未给。**建议**：`status == MOVING`（RESTING/ENGAGED 不自动回归；最保守，可用代码判）。
- **U6 物化方式**：P8 说"不物化冻结 `Route`"，但未说每 tick 写不写 `movement`。**建议**：每 tick 用 A\* 从当前位置重算到目标**当前** `effectivePosition`，并**写出一个新的 `Movement`**（`departedAt = tick 当前时刻`）；**持久事实只有 `rejoinTarget` 引用**，hex 序列绝不持久。若要"直接挪一格"版，另立裁定。

**bite-sized 步骤**

1. `setRejoinTarget(state, id, target?)`：目标不存在 ⇒ 拒；自指 ⇒ 拒；清 ⇒ `empty`。
   ⇒ 验证：三条用例绿。
2. `UnitState`/op 上的"有能力回归"纯判定：`effectivePosition(id)` present ∧ 目标**当前** `effectivePosition` present ∧ A\* 可达（用 `PathFinder.findPath`）∧ `status == MOVING`（U5 裁定）。**每 tick 现算、不存布尔**。
   ⇒ 验证：不可达/目标位置不可确定/非 MOVING 各为假。
3. `UnitTimeParticipant.simulate` 增一段：对每个 `rejoinTarget` present 的单位，满足"有能力回归" ⇒ 重规划并产出本 tick 的 `movement`/`position` 变更（U6 裁定形态）；不满足 ⇒ 不动（不进变更集）。
   ⇒ 验证：`UnitTimeParticipantTest` 新增条绿。
4. ★ **随动用例（m1 靶子）**：父单位从 `A` 移到 `B` ⇒ **下一 tick** 重规划的终点 = `B`（不是 `A`）；断言路径终点等于目标**当前** `effectivePosition`。实现里**不存在**"终点 hex"持久字段（可加结构性断言）。
   ⇒ 验证：该断言绿。
5. 边界：目标不存在（构造期不允许）⇒ 拒；目标位置不可确定 ⇒ 不建回归。
   ⇒ 验证：对应拒绝/不动用例绿。

**判据（可实测值）**
- `SetRejoinTarget` 目标不存在/自指 ⇒ 拒；设置/清除往返。
- "有能力回归"为假（不可达 / 非 MOVING）⇒ 无回归变更（**m2/m3 靶子**）。
- 大编制移动后终点**随动**：终点 == 目标**当前** `effectivePosition`（**m1 靶子**）。
- 状态里无"旧格"持久事实（结构断言）。
- `simos-unit` 计数累加；前端 88/88 不变。

**变异思路（≥2 轮）**
- **m1**：把目标存成**冻结 hex**（或缓存终点）⇒ 父动后仍指旧格 ⇒ 红。
- **m2**：恒建（不判可达）⇒ "不可达时无路径"红。
- **m3**：删"状态允许移动"判据 ⇒ 非 MOVING 也回归 ⇒ 红。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t7-evidence/`（含 U4/U5/U6 裁定记录）。

**提交信息样式**：`unit-ext T7：回归路径（SetRejoinTarget + participant 每 tick 重规划；终点随动不冻结；U4~U6 裁定在案）`

---

### T8 战损增量 `unit.ApplyCasualties`（E4 / N3 / P14）

**目标**：人员 + 装备**双轨 delta**、上界 `|Δ| ≤ 当前值`、**未知装备键拒绝**、绝对值落 revision ⇒ 时间线恢复。

**依赖**：T1。

**文件清单**

新增 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/spi/ApplyCasualtiesHandler.java`（`unit.ApplyCasualties`：`id, personnel, equipment{}`，值为 ≤0 的增量）

修改 main：
- `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java`（`applyCasualties`）

修改 test：
- `simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java`
- `simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`
- 新增 `simos-core/src/test/java/io/mosire/simos/core/UnitCasualtyRevisionTest.java`（真 `CommandBus` + 真 store：战损后回退前一 revision ⇒ 战前值；**core test scope 已放行 unit**）

**bite-sized 步骤**

1. `applyCasualties(state, id, personnelDelta, equipmentDeltas)`：双轨；只接受 `≤ 0`；**逐项上界**（`|Δ| ≤ 当前值`，越界 ⇒ 抛）；**未知装备键 ⇒ 抛**（P14；不视作 0）；结果 `member ≥ 0`、装备值 `≥ 0`（`Unit` 构造期继续把守）。
   ⇒ 验证：`100 + (−30) = 70`（**不是 30、不是覆写**）；`Δ=−101`（当前 100）⇒ 抛；未知键 ⇒ 抛。
2. handler：`personnel` 用 `requireInt`、`equipment` 用 `requireEquipment`；产 `UnitChangeSet.between(base, next)`（**不另造增量路径**）。
   ⇒ 验证：happy/上界/未知键/正 Δ 各用例绿。
3. 装备双轨用例：base `{"步枪":50,"炮":4}`，Δ `{"步枪":-10}` ⇒ `步枪=40`、`炮=4`（未提及键不变；**m3 靶子**）。
   ⇒ 验证：逐键断言。
4. 时间线恢复（`simos-core` 用例）：经真 `CommandBus` 提交 `unit.ApplyCasualties`（落 revision R2）⇒ 在 R1（战损前）上 `Replay` ⇒ `member`/`equipment` == 战前值。
   ⇒ 验证：该用例绿（**m5 靶子**）。
5. 事件无明文：断言 `received` 事件载荷**只含 digest**、不含 delta 明文（`CommandBus.java:377` 口径）。
   ⇒ 验证：该断言绿（**m6 靶子**）。
6. 正 Δ / 缺失字段 / 非 JSON ⇒ 拒（不逃逸异常）。
   ⇒ 验证：`UnitCommandHandlersTest` 新增条绿。

**判据（可实测值）**
- `base.member=100, Δ=−30 ⇒ new.member=70`（**m1 靶子**）。
- `Δ=−101`（当前 100）⇒ 拒、`revisions` 行数不变（**m2 靶子**）。
- 装备双轨：只扣提及键，未提及键不变（**m3 靶子**）。
- 未知装备键 ⇒ 拒（**m4 靶子**）。
- 时间线恢复：战损后回退前一 revision ⇒ 战前值（**m5 靶子**）。
- `received` 事件载荷无明文 delta（**m6 靶子**）。
- `simos-unit` 计数累加；`simos-core` 计数 `169 + K`；前端 88/88 不变。

**变异思路（≥2 轮，逐条配一条上文靶子）**
- **m1**：把 Δ 当绝对值（覆写）⇒ 结果 30 ⇒ 红。
- **m2**：删上界校验 ⇒ 负值/通过 ⇒ 红。
- **m3**：整表替换装备 ⇒ 未提及键丢 ⇒ 红。
- **m4**：未知键视作 0 忽略 ⇒ 红。
- **m5**：让战损覆写历史（或绕过 revision）⇒ 回退不恢复 ⇒ 红。
- **m6**：把 delta 塞进事件载荷 ⇒ "无明文"红。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t8-evidence/`。

**提交信息样式**：`unit-ext T8：战损增量 unit.ApplyCasualties（双轨 delta + 上界 + 未知键拒 + 时间线恢复）`

---

### T9 SPI 装配（`Shell.java:193-213` 一带）

**目标**：把 12 条新 handler 注册进 `Shell`（含 `PlanSparseRouteHandler` 的 `MovementCost` 注入）；catalog 含全部新 type；确认 `unit` namespace 恰一个 participant。

**依赖**：T2~T8 全部。

**文件清单**

修改 main：
- `simos-app/src/main/java/io/mosire/simos/app/Shell.java`（handlers 列表 + `new PlanSparseRouteHandler(TerrainMovementCost.INSTANCE)`；★ 直读现状 `:187-207`）
- `simos-app/src/main/java/io/mosire/simos/app/ShellConfig.java`（**仅当**需要新注入；预期**否**）

修改 test：
- `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java`（catalog ⊇ 12 新 type；逐条）

**bite-sized 步骤**

1. 在 `Shell` 的 handlers 列表追加 12 条：`AttachUnitHandler`、`DetachUnitHandler`、`ReparentSubtreeHandler`、`SetFormationOffsetHandler`、`CreateCommandChainHandler`、`UpdateCommandChainHandler`、`SplitFormationHandler`、`MergeFormationHandler`、`PlanSparseRouteHandler(cost)`、`SetRejoinTargetHandler`、`SetStatusHandler`、`ApplyCasualtiesHandler`。
   ⇒ 验证：`Shell` 启动不抛（`CommandRegistry` 构造期校验 `type` 形状）。
2. `commandTypes` 循环自动收全（既有 `:203-207`）；确认 catalog 读到的集合含全部新 type。
   ⇒ 验证：app 测试断言逐个 type。
3. `unit` namespace 恰一个 participant：装配只注册既有 `UnitTimeParticipant`（**不新增 unit participant**）；由 `TimeAdvance.putIfAbsent` 既有护栏把守（若误加第二个 ⇒ 装配/推进抛）。
   ⇒ 验证：`Shell` 启动 + 一次 `advance` 不抛。
4. 端到端冒烟：经 `Shell` 发一条新命令（如 `unit.SetStatus`）⇒ `committed`、`/api/command` 同路。
   ⇒ 验证：`ShellEndToEndTest`/冒烟条绿。

**判据（可实测值）**
- `catalog` 的 type 集合 ⊇ `{unit.AttachUnit, unit.DetachUnit, unit.ReparentSubtree, unit.SetFormationOffset, unit.CreateCommandChain, unit.UpdateCommandChain, unit.SplitFormation, unit.MergeFormation, unit.PlanSparseRoute, unit.SetRejoinTarget, unit.SetStatus, unit.ApplyCasualties}`（**m1 靶子**）。
- 每个新 `type()` 形状 `<namespace>.<Command>`（构造期已校验；无点 ⇒ 构造期抛）。
- `unit` namespace 恰一个 participant（装配 + advance 不抛）。
- `simos-app` 计数 `110 + M`；`simos-unit` 已含 T1~T8；前端 88/88 不变。

**变异思路（≥2 轮）**
- **m1**：删一条 handler 注册（如 `SetStatusHandler`）⇒ catalog 少一个 ⇒ app 断言红。
- **m2**：`PlanSparseRouteHandler` 注入不可通行的 `MovementCost` 替身 ⇒ 稀疏路线冒烟红。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t9-evidence/`。

**提交信息样式**：`unit-ext T9：SPI 装配（Shell 注册 12 新 handler + cost 注入 + catalog 全 type）`

---

### T10 端到端判据 + 关账

**目标**：spec §八 20 条**逐条实测值**（不是"通过/不通过"，要**数字**）；变异轮汇总；**"我未能核实的"清单**；关账报告。

**依赖**：全部。

**文件清单**

新增：
- `simos-app/src/test/java/io/mosire/simos/app/UnitExtensionEndToEndTest.java`（真 `Shell`/`CommandBus` + 真 store 的端到端；覆盖判据 1/5/6/7/8/12/15 的链路）
- `.superpowers/sdd/2026-09-20-unit-extension/t10-evidence/`（`clean-verify.log`、`verify-rc.txt`、变异日志）
- `.superpowers/sdd/2026-09-20-unit-extension/task-10-final-report.md`

**bite-sized 步骤**

1. 写 e2e：真装配 + 真 store，按 spec §八 #1/#5/#6/#7/#8/#12/#15 各断言**数字**（如"同一 unit ∈ 2 链 ⇒ `commandChains.size()==2`"、"迁移后 `b.parent==a` 逐 id"、"稀疏 path 长度 3"、"战损后回退 `member==100`"）。
   ⇒ 验证：定向绿。
2. 逐模块对差：`util 170 / map 362 / social 45 / unit 131+N / core 169+K / app 110+M`，总数 = `987 + N + K + M`；`BugInstance size is 0` ×6；`[ERROR]` 0 行；前端 `tests=88 pass=88 fail=0`。
   ⇒ 验证：主树 `./mvnw clean verify` 前台跑（★ 后台会被内存守卫杀）。
3. 变异汇总表：逐任务列出变异体、期望红点、实际结果（KILLED/SURVIVED）；**存活项如实存档**，不伪造红点。
   ⇒ 验证：表与各 `tN-evidence/` 日志一致。
4. 写 `task-10-final-report.md`：判据 20 条逐条落点 + 实测值；**criterion 20 记 out-of-scope**（归 sd 里程碑）；"我未能核实的"（见 §六）；带裁定的遗留。
   ⇒ 验证：报告无占位符。

**判据（可实测值）**
- spec §八 20 条（criterion 20 记 out-of-scope 并给理由）。
- `./mvnw clean verify` rc=0；总数 = `987 + Σ新增`；`util/map/social` 逐值不变；前端 88/88。
- 变异轮**开跑前先自证 md5**、**读 surefire 核对报告 mtime 落本轮内**。

**变异思路**：对 T10 新加的端到端断言各配 ≥1 变异（至少：删 `MergeFormation` 的同格前置 ⇒ e2e 红；删 `ApplyCasualties` 上界 ⇒ e2e 红）。

**证据落点**：`.superpowers/sdd/2026-09-20-unit-extension/t10-evidence/`。

**提交信息样式**：`unit-ext T10 关账：20 条判据逐条实测值 + 变异汇总 + 逐模块对差；clean verify rc=0 <总数>`

---

## §四 执行期取代说明（留空节）

> 本计划中的**代码草图 / 路径 / 名字 / 注入形态**是计划期产物。执行期的每一处取代，按以下格式**逐条追加**到本节点下，并在 `.superpowers/sdd/2026-09-20-unit-extension/progress.md` 留对应裁定：
>
> ```
> ### 取代 <编号>（<任务>，<日期>）
> - 原计划：<原文>
> - 实际：<落盘事实 + `文件:行`>
> - 理由：<为何原计划不成立>
> - 影响面：<波及哪些任务 / 哪些判据>
> ```

**已知的待落定取代候选（执行期确认）**：
- **S1（T1）**：`attached`/`offset` 若 `SegmentedSeries<Boolean>` 往返失败 ⇒ 退化为普通字段（spec §九未核实项）。
- **S2（T2）**：`effectiveSpeed` 的 `max(1,·)` clamp（缺口 U1）。
- **S3（T5）**：`disband` 与链引用（缺口 U2）。
- **S4（T6）**：`PlanSparseRouteHandler` 的 `MovementCost` 注入形态（缺口 U3）。
- **S5（T7）**：`rejoinTarget` 存储形态 / "状态允许移动"定义 / 物化方式（缺口 U4~U6）。

---

## §五 纪律与门禁

1. **铁律**：查询解析为稳定实体；写路径只能 `Command → ChangeSet → Revision`；领域模块只拥有自己数据；Core 只组合调度；变更集从完整状态派生且有往返不变式守卫（本计划 T1 的 `UnitRoundTripTest` 就是它）。
2. **模块边界**：`simos-unit` 只依赖 `simos-util` + `simos-map`；**不碰 social/core/agentlib**（`bannedDependencies` 构建期强制）。本计划**不新增模块依赖**、**不改任何 enforcer**。
3. **一次只跑一个 Maven**；`mvn test` 不跑 SpotBugs ⇒ 关账前单独 `spotbugs:check`（或直接 `verify`）。
4. **不 `git add -A`**；显式路径；**不加 `Co-Authored-By`**；该推就推。★ 不碰 `.superpowers/sdd/2026-09-19-*` 与任何 spec。
5. **护栏必须自证**：每个新护栏配故意违规用例 + ≥2 轮变异（九道门禁，见 §〇）。
6. **关账门禁**：主树 `./mvnw clean verify` 前台跑，rc=0、逐模块计数、`BugInstance size is 0` ×6、`[ERROR]` 0、前端 `tests=88 pass=88 fail=0`。★ 若 `clean verify` 被杀：**"被杀"既不是红也不是绿**，留档不删、前台重跑。

---

## §六 风险与挂起

**待裁缺口（已在任务内标 ★，执行期必须记台账）**

| # | 缺口 | 建议 | 落点 |
|---|---|---|---|
| **U1** | `effectiveSpeed = speed × factor / 1000` 可能 < 1（如 `speed=2`、ENGAGED ⇒ 0），与 `Movement.speedAtDeparture ≥ 1` 冲突 | `max(1, floorDiv(speed×factor+500,1000))`；判据夹具用 `speed ≥ 4` 保证三档可区分 | T2 |
| **U2** | `disband` 删单位会让 `commandChains` 引用悬空（违反 §一.2 不变量 2） | `disband` **拒绝**当该单位仍是任何链的 commander/member（先修链） | T5 |
| **U3** | `unit.PlanSparseRoute` 如何取得 `MovementCost`（spec 未说） | handler 构造器注入 `MovementCost`；`Shell` 传 `TerrainMovementCost.INSTANCE`（与 participant 同源） | T6 / T9 |
| **U4** | `rejoinTarget` 存储形态（spec §二.2 只说"记意图"） | `Unit.rejoinTarget: Optional<UnitId>` 普通字段（与 `status` 同族；历史由 revision 承载） | T1/T7 |
| **U5** | P7"状态允许移动"未定义 | `status == MOVING`（最保守；RESTING/ENGAGED 不自动回归） | T7 |
| **U6** | P8"不物化冻结 `Route`"未说每 tick 是否写 `movement` | 每 tick A\* 重算并写一个新 `Movement`（`departedAt=tick`）；**持久事实只有引用**，hex 序列绝不持久 | T7 |

**风险**

- **R1（编译耦合）**：`Unit` 加 record 组件会打断全仓约 40 处构造点。**对策**：T1 一次落地 + 兼容构造器（旧 9 参签名），production 拷贝点走 canonical。**残留**：兼容构造器易被误用丢字段 ⇒ T1 步骤 8 显式列 production 拷贝点。
- **R2（spec §九 未核实）**：`SegmentedSeries<Boolean>` 是否可序列化；`CommandChain`/`RelativeOffset`/`UnitStatus` 的 Jackson 往返；`CommandChainId` 作 Map 键需键反序列化器。**对策**：T1 步骤 7 先验往返，失败走 S1 退化分支。
- **R3（impact 面）**：`effectivePosition` 加 offset 后对既有用例的冲击面（spec §九）。**对策**：默认值使旧行为逐字不变；T1 判据含"offset 为空 ⇒ 与今天逐字相同"回归条。
- **R4（稀疏拼接）**：A\* 分段拼接可能产生 `Route` 禁止的重复格或让 `waypoints` 子序列扫描错位。**对策**：交给 `Route` 构造期拒（命令期），T6 步骤 4 配"跨段重复 ⇒ 拒"用例。
- **R5（回归物化代价）**：每 tick 对每个在途回归单位跑 A\*，19441 格真图上的性能**未测**（spec §九）。**对策**：T10 记录实测/未测；若超预算，另立优化（或退化为 U6 的"直接挪一格"版）。
- **R6（链可寻址/关系枚举）**：P11 明确 v1 不做 ⇒ 本计划**不新增 resolver 子地址**、不改 `UnitResolver`；`UnitAgentAttachPolicy` 对链/编队**不自动可绑**（spec §五.3）。执行期不得顺手加。
- **R7（sd 边界）**：spec §八 criterion 20（sd 经 `SdCommandDrain` 写 unit）**超出本计划** —— sd 未实现、App 无 drain。**对策**：T10 把 #20 记成 out-of-scope 并写明理由；unit 侧只保证"不认识 sd、无跨模块直写"（铁律 3 的结构性，由 enforcer 把守）。
- **R8（`ArchitectureGuardsTest`）**：本次只在 `UnitChangeSet` 内加组件 ⇒ 实现者仍 4 个；**若有人另造 unit 变更集类 ⇒ 该测试会红**（不必改，但要知道）。
- **R9（旧档兼容）**：`unit.CreateUnit` 加默认段对旧档的影响假定不变（spec §九未实证）。**对策**：T1 默认值 + 既有 codec 往返用例覆盖；真档（`test_integration`）冒烟在 T10 记"已验/未验"。
- **R10（跨 JVM 字节稳定）**：新类型的序列化跨 JVM 稳定性未测；本计划不承诺。

---

## 附：命令清单速查（P10 裁定名，本计划 12 条）

| type | 落点任务 |
|---|---|
| `unit.AttachUnit` | T3 |
| `unit.DetachUnit` | T3 |
| `unit.SetFormationOffset` | T3 |
| `unit.ReparentSubtree` | T4 |
| `unit.SplitFormation` | T4 |
| `unit.MergeFormation` | T4 |
| `unit.CreateCommandChain` | T5 |
| `unit.UpdateCommandChain` | T5 |
| `unit.PlanSparseRoute` | T6 |
| `unit.SetRejoinTarget` | T7 |
| `unit.SetStatus` | T2 |
| `unit.ApplyCasualties` | T8 |
