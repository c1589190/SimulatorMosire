# T1 执行报告 —— 模型地基（Unit 扩容计划 §三 T1）

日期：2026-09-20。worktree：`.claude/worktrees/uet12`，分支 `ue/t12`（T1+T2 同树，逐任务提交）。

## 一、落地内容（main）

新增：
- `UnitStatus`（三态 enum；T1 只定义三值，T2 加 `factorPerMille`）
- `RelativeOffset(int dq, int dr)` + `appliedTo(HexCoord)`（P2：合法 HexCoord，不强制落图内）
- `CommandChainId`（与 `UnitId` 同形三件套）
- `CommandChain(CommandChainId id, String name, UnitId commander, Set<UnitId> members)`（构造期：commander∈members、members 非空、LinkedHashSet + unmodifiableSet 冻在赋值处）

修改：
- `Unit` +4 record 组件（`status` / `attached` / `offset` / `rejoinTarget`）+ 兼容构造器（旧 9 参 ⇒ 以 `parent.segments().get(0).from()` 为锚造 `attached=true` / `offset=empty`；`status=MOVING`；`rejoinTarget=empty`）
- `UnitState` +`commandChains` 组件（LinkedHashMap + unmodifiableMap）+ 兼容 1 参构造器 + `withCommandChains`；构造期新增 `requireChainReferencesResolve`（commander/全 members 必须存在于 `units`）
- `UnitState.effectivePosition` 按 spec §一.4 五行情形重写（自身位置优先；attached 且 offset 非空 ⇒ 父有效位 ⊕ offset；attached 且 offset 空 ⇒ 父位；detached 且无自身位置 ⇒ 空，不回退父）
- `UnitChangeSet` 二组件（`units` + `commandChains`），`between`/`apply`/`isEmpty` 同步
- `UnitCodec.keyModule()` 注册 `CommandChainId` 键反序列化器
- canonical 拷贝点连带：`UnitOperations.copy`、`UnitMoves.evaluate` 的 frozen 视图、`UnitTimeParticipant.withPositionAndMovement`、`CreateUnitHandler` 一律走 13 参 canonical（不用兼容构造器，避免丢字段）

## 二、判据实测值（simos-unit 定向轮）

| 判据 | 实测值 |
|---|---|
| 旧行为回归（既有用例） | `UnitStateTest` / `UnitOperationsTest` / `UnitMovesTest` / `UnitTimeParticipantTest` 全绿 |
| attached、无自身位置、offset=(2,-1) | `effectivePosition` = 父位 ⊕ (2,-1) = `(4,1)`，`isNotEqualTo(Optional.of(H22))` 通过 |
| detached、无自身位置 | `Optional.empty()`（不回退父）通过 |
| attached、offset 空 | = 父位（与 M3 逐字相同的回归条）通过 |
| offset 沿父链复合 | 孙子 = 祖父位 ⊕ 父偏移 ⊕ 自己偏移 通过 |
| 同一 unit ∈ 2 条链 | `UnitCodecTest.snapshotRoundTripsCommandChainsWithASharedMember` 往返后两条都在 |
| 悬空链引用 ⇒ 抛 | `danglingChainReferencesAreRejectedAtConstruction` 绿（commander 与 member 各一条） |
| commandChains 迭代序 == 插入序 | `commandChainsKeepInsertionOrder` 绿（`containsExactly(c-2, c-1)`） |
| `UnitRoundTripTest` 全绿 | 绿（`changeSetHasExactlyTwoComponents` + 组件枚举往返） |
| `ArchitectureGuardsTest` 计数仍 5 | 未改（本次只在 `UnitChangeSet` 内加组件），关账轮 clean verify 复核 |
| **simos-unit 计数** | **148**（= 原 131 + 17：RelativeOffset 3 + CommandChain 4 + UnitStatus 1 + UnitStateTest +7 + UnitCodecTest +2 + UnitRoundTripTest 0） |
| app/core 编译审计 | `./mvnw -q -pl simos-app,simos-core -am -DskipTests test-compile` rc=0，`[ERROR]` 0 行 |

`util 170 / map 362 / social 45` 未动（本轮只改 simos-unit 及其上游）。

## 三、S1（退化分支）未触发

计划 §四 S1：`SegmentedSeries<Boolean>` 往返失败 ⇒ `attached`/`offset` 退化普通字段。**实测可往返**：`UnitCodecTest` 的既有快照往返用例现在会序列化 `attached`（`SegmentedSeries<Boolean>`）与 `offset`（`SegmentedSeries<Optional<RelativeOffset>>`），全绿 ⇒ **S1 不触发**，时态序列形态保留。

## 四、变异轮（九道门禁，`mutants/mut-round.sh`）

装置：清 `target/classes`、`target/test-classes`、`target/surefire-reports`（①④）；原件/变异体 md5 比对且不同（②）；变异体就是目标类名、直接 cp 至 TARGET（③）；`-Dtest=<类名>` 定向（⑤）；日志自指追加四个 md5 + `compile_errors` + surefire summary + report mtime（⑥⑨）；`cp` 逐字节 `$BACKUP` 还原（⑧）。

| # | 变异体 | 靶子 | 实测 | 红点 |
|---|---|---|---|---|
| m1 | `effectivePosition` 删掉 `offset.get()::appliedTo`（忽略偏移） | 父位⊕offset 两条 | **KILLED** rc=1 / Failures 2 / compile_errors 0 | `UnitStateTest.java:179`、`:229` |
| m2 | 删 detached 分支（回退父位） | detached ⇒ 空 | **KILLED** rc=1 / Failures 1 / compile_errors 0 | `UnitStateTest.java:192` |
| m3 | `requireChainReferencesResolve` 空体（不查引用完整性） | 悬空引用 ⇒ 抛 | **KILLED** rc=1 / Failures 1 / compile_errors 0 | `UnitStateTest.java:246` |
| m4 | `changedOf` 的 commandChains 分支改成 `false`（不登记） | 反射枚举要求非 Unchanged | **KILLED** rc=1 / Failures 1 / compile_errors 0 | `UnitRoundTripTest.java:65`「组件 commandChains 必须被 between 报成非 Unchanged」 |

**存活：0。** 四轮每轮 `restored_md5 == orig_md5`（cp 逐字节还原，未用 `git checkout --`）。日志：`logs/m1.log`~`logs/m4.log`（自记块在文件尾）。

## 五、我未能核实的

1. **跨 JVM 字节稳定**：新类型（`CommandChain`/`RelativeOffset`/`UnitStatus`）的序列化跨 JVM 稳定性**未测**（计划 R10 明确不承诺）。
2. **真旧档兼容**：`unit.CreateUnit` 加默认段对**真实存档**（`test_integration`）的影响**未在本任务实测**（只有 codec 往返用例覆盖；真档冒烟归 T10）。
3. **`offset` 不落图内的实际使用**：只证了「不强制落图内」的构造合法（`RelativeOffset` 无范围约束），未构造「父位在边界、子偏移越界」的真地图用例。
4. **`noPositionAnywhereIsEmpty` 的 `Map.of` 写法未改**（它直接用 1 参兼容构造器；语义等价）。
5. **SpotBugs 未在本任务单独跑**（计划归关账轮 `clean verify`；T1 只跑了 `test` 与 compile 审计）。

## 六、执行期取代说明

无。计划 §三 T1 的步骤、文件与判据**逐条落地**；`SegmentedSeries<Boolean>` 未触发 S1 退化分支。
