# SDSimos — A3 + A4 + A5 + A6 执行报告

> 范围：计划 `docs/superpowers/plans/2026-09-20-sd-simos-plan.md` 的 **§三 A3 / A4 / A5 / A6** 与 **§〇 通则**。
> 设计依据：`docs/superpowers/specs/2026-09-20-sd-simos-design.md`（§三 / §四 / §六 / §九）。
> 分支：`sd/a3a6`（worktree `.claude/worktrees/sda3a6`）；基线 `924bb49`（A1+A2 合并后 HEAD）。
> 实现提交：**`3dbd311`**（`feat(sd): SdState/SdChangeSet/SdCodec 与 Nation 命令族及写前守卫（A3~A6）`）。台账：`.superpowers/sdd/2026-09-20-sd-simos/progress.md`。

---

## 一 落地物

| 任务 | 落地物（main） | 测试（test） |
|---|---|---|
| **A3** | `sd/state/SdState`（10 组件 + 5 条不变量）、`sd/state/SdSnapshot`、`sd/change/SdChangeSet`（10 组件）、`sd/codec/SdCodec`（第 4 个 `ModuleCodec`，9 个 ID 键反序列化器）；`ArchitectureGuardsTest` 4→5；`Shell` 注册 `SdCodec`；`Combat.stages` 改 `List<CombatStage>` | `SdRoundTripTest`(5)、`SdStateInvariantTest`(6)、`SdCodecTest`(5) |
| **A4** | `sd/spi/{CreateNation,CreateArmy,CreateDecisionMaker}Handler`、`NationTag`、`SdCommandNames`、`SdPayloads`、`SdSnapshots`、`sd/resolve/SdResolver`；`Shell` 注册 3 handler + 1 resolver | `CreateNationHandlerTest`(4)、`CreateArmyHandlerTest`(4)、`CreateDecisionMakerHandlerTest`(5)、`SdResolverTest`(8) |
| **A5** | `sd/spi/PutInfoHandler`；`Shell` 注册 | `PutInfoHandlerTest`(5)、`app/SdPutInfoEndToEndTest`(3，真 `CoreSimos`+真 `Replay`) |
| **A6** | `util/spi/MutationGuard`；`CommandBus`（handler **前**调 guard）+ `CoreSimos.register(MutationGuard)`；`sd/guard/RegionDeleteGuard`；`Shell` 装配 | `core/command/CommandBusGuardTest`(5)、`sd/guard/RegionDeleteGuardTest`(7)、`app/SdRegionDeleteGuardEndToEndTest`(3，经 `Shell`) |

工作树夹具：`sd/testing/SdFixtures`（10 组件全非空的合法 `SdState` + 逐组件变体）、`sd/testing/SdWorlds`（带国家 tag 区域 / 未 tag 区域 / 根单位 / 三层切片）。

---

## 二 门禁（`./mvnw clean verify`，前台）

命令：`./mvnw -o clean verify`。证据：`a3-evidence/clean-verify-final.log`、`a3-evidence/verify-rc.txt`。

| 项 | 实测值 |
|---|---|
| `rc` | **0**（`BUILD SUCCESS`） |
| 模块 | **8/8 SUCCESS**（父 + util/map/social/unit/core/sd/app） |
| 用例总数 | **1060** = util **170** / map **362** / social **45** / unit **131** / core **174** / sd **62** / app **116** |
| `BugInstance size is 0` | **×7** |
| `[ERROR]` | **0 行** |
| 前端门禁 | `[frontend-gate] OK tests=88 pass=88 fail=0` |

### 增量逐模块解释（相对任务基准 **987** = 170/362/45/131/169/110，7 模块）

| 模块 | 基准 → 实测 | 增量 | 来源 |
|---|---|---|---|
| util | 170 → **170** | 0 | 只新增 `MutationGuard` 接口（无逻辑，不配装饰性用例——转发面由 `CommandBusGuardTest` 钉住） |
| map | 362 → **362** | 0 | 零改动 |
| social | 45 → **45** | 0 | 零改动 |
| unit | 131 → **131** | 0 | 零改动 |
| core | 169 → **174** | **+5** | `CommandBusGuardTest`（A6） |
| sd | 0 → **62** | **+62** | A1+A2 既有 13；A3 +16、A4 +21、A5 +5、A6 +7 = **+49** ⇒ 13+49=62 |
| app | 110 → **116** | **+6** | `SdPutInfoEndToEndTest` 3（A5）+ `SdRegionDeleteGuardEndToEndTest` 3（A6） |
| **合计** | 987 → **1060** | **+73** | 62（sd）+5（core）+6（app） |

★ **与计划 §附推演表的偏差**（实测为准）：计划推 A4 app +0、A5 app +0；实际 A4/A5 在 `Shell` 注册 3+1 个 sd handler，**牵动 M5 的两条 catalog 覆盖测试**（`SimosToolsTest.catalogListsExactlyTheRegisteredCommandTypes`、`McpCoverageTest.everyCatalogTypeIsReachableThroughMcpAndTakesEffect`）——它们硬编码"catalog == Shell 注册的 handler 集合"。已按新语义扩展到 18 类（含 4 条 sd 命令 + 最小载荷 + genesis 加 sd 切片 + tagged 区域），**保持 `containsExactly` 严格性，未改成恒真或 `hasSize`**。`ShellSmokeTest` 的 codec 计数 3→4 同理。

---

## 三 判据实测（逐条）

### A3
- **往返**：反射枚举 `SdState` 10 组件逐组件造差异 ⇒ `changeSet 非空` + `changed 为真` + `apply==target` 全绿；全组件夹具经 `RoundTripAssertions.assertRoundTrip` 绿。
- **架构计数**：`ArchitectureGuardsTest` 命中**恰 5 个** main `ChangeSet` 实现者（World/Map/Sd/Social/Unit）。
- **编码器**：`SdCodec` 快照 encode→decode 相等；encode→decode→**再 encode 逐字节相同**；四条 `FieldDelta` 变体各造一条并往返；外部切片（非 `SdSnapshot`）⇒ `IllegalStateException`（消息含"不是 SdSnapshot"）。
- **5 条构造期不变量**各配故意违规用例：R4 重复 / 悬空外键 / 结局不在表 / 阶段链断裂 / 损失记录为空+跨 Combat ⇒ 构造抛。

### A4
- 三条命令：合法 ⇒ `Applied`（`revisions` +1）；重复 id / 缺失外键 / 无 tag 区域 / 白名单含通用写 ⇒ `Rejected`，**拒绝路径不留 revision**（handler 纯拒，由 `CommandBus` 折事件）。
- `CreateNation` 无 tag 区域 ⇒ 拒绝、消息含 region id（`"Region r2 无国家 tag（R13：需以 nation: 开头的 tag）"`）。
- `CreateDecisionMaker` 的 `allowedTools` 含 `simos.command.submit` ⇒ 拒绝、消息点名 N9。
- `SdResolver`：`sd:nation.n1` / `sd:army.a1` / `sd:decision-maker.dm1` / `sd:directive.d1` / `sd:effect.e1` canonical 回显；`sd:combat.c1:stage.s1`（3 段）与 `...:outcome.o1`（4 段）；未存在 / 未知 kind / 缺 kind / 跨命名空间 ⇒ **空候选**；`o2` 不在 `s1` 的表里 ⇒ 空候选。

### A5
- `sd.PutInfo` 经真 `CoreSimos.submit` ⇒ `(main,2)`；`Replay((main,2))` 的 `info["map:Map1:region.r1"]` **逐字段**等于 `SdInfoEntry("brief","hello",Optional.of("n"),RevisionId(1),Optional.empty())`（`at` = 计算时 base 的 revision）。
- 同址两次 ⇒ 追加成两条且都可重放。
- 坏地址（`"oops"`）⇒ `Rejected`、`head` 不动（`RevisionId(1)`）。

### A6
- 经 **`Shell`**（真装配点）提交：带 tag 的 `map.DeleteRegion` ⇒ `Rejected`（消息含 `r1` + "国家 tag"）、`head` 不动、事件链 `received+rejected`。
- ★ **去 tag 放行**（`r2` ⇒ `Committed`、`head` 2）——证明**不是恒拒**。
- 非 `map.DeleteRegion`（`sd.PutInfo`）⇒ guard 返回空、照常提交。
- `RegionDeleteGuard` 真值表 7 条：tag 拒 / 去 tag 放行 / `homeRegion` 引用也拒 / 其他命令忽略 / 缺区域与坏载荷放行 / 确定性（两次调用结果相同）。
- `CommandBusGuardTest` 5 条：守卫在 handler 前短路 / 拒绝落 `received+rejected` / 放行则 handler 跑 / **type 与载荷逐字节原样转交**（R11 对 guard 同样成立）/ 注册序 = 调用序且首个拒绝后不再往下调。

---

## 四 变异轮（九道门禁）

装置：`.superpowers/sdd/2026-09-20-sd-simos/a3-evidence/mutants/mut-round.py`（逐轮：备份 → 改源 → **自证 mutant md5 ≠ orig md5** → 跑定向门禁 → **断言 `COMPILATION ERROR`=0** → 提取失败行并核对落点 → `cp` 逐字节还原 → **自证 restored md5 == orig md5** → 把本轮 md5 追加进日志）。汇总：`a3-evidence/mutants/run.log`。

| 变异体 | 做法 | 结果 | 红点（被保护断言） |
|---|---|---|---|
| a3-m1 | `SdChangeSet.between` 的 `info` 恒 `Unchanged` | **KILLED** | `SdRoundTripTest`（info 组件 changed 为假） |
| a3-m2 | `SdChangeSet.apply` 不重建 `combats` | **KILLED** | `SdRoundTripTest`（combats 往返不等） |
| a3-m3 | `ArchitectureGuardsTest` 删 sd 路径 | **KILLED** | `ArchitectureGuardsTest`（actual 5 ≠ expected 4） |
| a3-m4 | `SdCodec` 删 `NationId` 键反序列化器 | **SURVIVED（等价）** | 无（见下） |
| a3-m4b | `SdCodec.asSdSnapshot` 改裸 cast | **KILLED** | `SdCodecTest.applyAndEncodeSnapshotRejectForeignSlice`（`ClassCastException` ≠ `IllegalStateException`） |
| a4-m1 | `CreateNation` 删 R13 tag 校验 | **KILLED** | `CreateNationHandlerTest.rejectsRegionWithoutNationTag` |
| a4-m2 | `CreateDecisionMaker` 删 N9 白名单校验 | **KILLED** | `CreateDecisionMakerHandlerTest.rejectsGenericWriteInAllowedTools` |
| a4-m3 | `SdResolver` 无视存在性 | **KILLED** | `SdResolverTest.unknownEntityYieldsNoCandidates` |
| a5-m1 | `PutInfoHandler` 跳过 `Address.parse` | **KILLED** | `PutInfoHandlerTest.rejectsMalformedAddress` |
| a5-m2 | `SdChangeSet.apply` 漏 `info` 归一 | **KILLED** | `PutInfoHandlerTest`（info 未落） |
| a6-m1 | `Shell` 删守卫装配 | **KILLED** | `SdRegionDeleteGuardEndToEndTest.taggedRegionDeleteIsRejectedAndLeavesNoRevision` |
| a6-m2 | `RegionDeleteGuard` 恒拒 | **KILLED** | `RegionDeleteGuardTest.untaggedRegionIsAllowed` |
| a6-m3 | `CommandBus` 把 guard 调用挪到 handler **之后** | **KILLED** | `CommandBusGuardTest.rejectingGuardShortCircuitsBeforeTheHandler`（替身 handler 先抛） |

**合计：13 个变异体，12 KILLED / 1 等价存活 / 0 落错位置。**

### a3-m4 存活根因（"没红也要问为什么没红"）
- **不是没跑到**：该轮跑后（21:31:59，落本装置窗口内）`simos-sd/target/surefire-reports/io.mosire.simos.sd.codec.SdCodecTest.txt` = `Tests run: 5, Failures: 0`；复跑一轮同结论。
- **是等价变异体**：`NationId` 是**单 String 分量 record**，Jackson 的默认键反序列化回退（String 构造）已能把 canonical 键串读回成 `NationId` ∀ 快照（含 `nations` 两键）往返相等。故删显式 `addKeyDeserializer` **不改变行为**。
- 结论：这些显式键注册在**本 Jackson 版本下对单 String record 非承重**（对 `Address` 这类无 String 构造的类型才是必需的——裁定 38）；保留显式注册是仓内既有形制与计划要求的一致做法。**补 a3-m4b** 提供一个可杀的 codec 变异体。

★ **surefire 报告带状态**（纪律形态 5）：装置还原的是**源文件**，`target/surefire-reports/*.txt` **不还原**——故 a3-m4b 轮结束后那份 `SdCodecTest.txt` 留着变异体的失败。**本轮结论取的是干净轮** `clean-verify-final.log`（rc=0、SdCodecTest 5/5 绿），不拿变异轮遗留的报告当结论。

---

## 五 执行期取代说明汇总

1. **`Combat.stages`：`List<CombatStageId>` ⇒ `List<CombatStage>`**（A3）。spec §三.3 与 §三.1（无阶段表）+ §三.1.3/§三.1.4（需读阶段内容）三处对齐的最小改动；组件数仍 10。
2. **`SdState.info` 键取 `Address#canonical()` 串**（A3）。`Address` 未重写 `toString`（既有用例钉死），作 `FieldDelta` 键会破往返 ⇒ 键取 canonical；`SdChangeSet.info` 为 `FieldDelta<List<SdInfoEntry>>`（spec §五.1 的 `FieldDelta<SdInfoEntry>` 与 §三.1 的 `List` 值自相矛盾，取 §三.1）。
3. **`SimosObjectMapper.addressValues()`**（A3）。`Address` 作**值**首次进快照树，需值绑定（键绑定管不到）。
4. **`SdState` 不 `implements Snapshot`**（A3）。spec §三.1 笔误；按树/切片分离。
5. **spec §三.1.5 的损失上界在构造期不可判**（A3）。落命令期 C3；A3 落地可判子集。
6. **`sd:combat` 链式地址 3 / 4 段**（A4）。计划写的 4/6 段是笔误（`kind.name` 在同一段内）。
7. **M5 两条 catalog 覆盖测试扩展**（A4/A5）。新 sd handler 进 catalog，测试按新语义扩展（严格列表，非恒真）。
8. **`ShellSmokeTest` codec 计数 3→4**（A3）。新语义，非为过门禁。
9. **A3-m4 等价变异体**（如实记）。

---

## 六 我未能核实的

- **`sd.PutInfo` 的结构化值往返**（Map/List）：未验；只覆盖标量（spec §六 / M4 裁定 38 同口径的挂起项）。
- **跨 revision 的 drain 原子性**：未涉及（C/D 阶段）。
- **守卫只覆盖 `map.DeleteRegion`**：spec §九 v1 范围；其余跨模块写前校验未预设。
- **A3-m4 等价性只对 `NationId` 实测**；未逐个 ID 类型复验（同形 record，推断相同——属"推导"非"实测"）。
- **`a6-m2`/`a5-m2` 等**使整类用例多条红：红点确认落被保护断言，但未做"最小杀伤面"分析。
- **前端 88/88 未变**：本批零前端改动。
- **`clean verify` 的 `[WARNING]` 数**未逐条清点（只记 `[ERROR]`=0）。
- **跨 JVM 的编码字节稳定性**：未测（沿用仓内既有口径）。
- 计划 §附推演表与实测的其它偏差：**只核了 A3~A6 相关项**，C/D 阶段的推演未核。

---

## 七 提交记录

- 实现提交：**`3dbd311`** —— `feat(sd): SdState/SdChangeSet/SdCodec 与 Nation 命令族及写前守卫（A3~A6）`
- 证据/报告/台账提交：**本报告所在提交**（`docs(sd): A3~A6 执行报告与台账`）
