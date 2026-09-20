# T5 报告：`command_chain` 命令族（Create / Update + 多属 + Map 键往返 + disband 交互裁定）

- 工作树 `/home/dev/SimulatorMosire/.claude/worktrees/uet5`，分支 `ue/t5`，基线 `3e7d33e`（干净）
- 只碰 `simos-unit`；`simos-app` / `simos-core` / `simos-util` / `simos-map` / `simos-social` / `simos-sd` **一行未动**（§〇 有实测）
- 证据目录：`.superpowers/sdd/2026-09-20-unit-extension/t5-evidence/`（`logs/` 29 份、`mutants/` 28 个变异体 + 装置、本报告）
- 判据来源：spec §一.2（`:69-90`）、§五.2 命令表（`:366-367`）、§五.3（`:387`）、§八 #1/#19（`:460-500`）；计划 `### T5`（`:329-378`）

---

## §〇 落点清单（与基线 `3e7d33e` 逐文件比）

**新增 main（2 个，untracked ⇒ 不在 `git diff` 里，另行列出）**

| 文件 | 行数 | `type()` |
|---|---|---|
| `simos-unit/.../unit/spi/CreateCommandChainHandler.java` | 61 | `unit.CreateCommandChain` |
| `simos-unit/.../unit/spi/UpdateCommandChainHandler.java` | 65 | `unit.UpdateCommandChain` |

**修改 main（3 个）**

| 文件 | 变更 | 内容 |
|---|---|---|
| `.../unit/ops/UnitOperations.java` | +154/−9 区段 | `createChain` / `updateChain` / `requireNotInAnyChain`（disband 前置，双向）/ 五处 `state.withUnits(next)` 修复（T5-U2） |
| `.../unit/spi/UnitPayloads.java` | +24/−1 | `optionalTextArray`（`members` 的 `[字符串…]` 或 null） |
| `.../unit/spi/UnitTimeParticipant.java` | +4/−2 | T5-U2 site 5：`snapshot.state().withUnits(units)` |

**修改 test（4 个，新增 `@Test` 恰 15 条）**

`ops/UnitOperationsTest.java`（+344）、`spi/UnitCommandHandlersTest.java`（+269）、`codec/UnitCodecTest.java`（+48）、`spi/UnitTimeParticipantTest.java`（+36）。

**未碰（实测 0 行差异）**：`git diff --stat 3e7d33e -- simos-app simos-core simos-util simos-map simos-social simos-sd` ⇒ **空**。`CLAUDE.md` 未改（本树与主树都没改）。

**模块门禁**：`./mvnw -o -pl simos-unit -am verify` ⇒ **rc=0**（`logs/verify-module.log`、`logs/verify-module-rc.txt`）
- reactor：UtilSimos SUCCESS 45.9 s / MapSimos SUCCESS 1:36 / UnitSimos SUCCESS 57.0 s / **BUILD SUCCESS**
- 用例：util **170** / map **362** / unit **216**（T4 关账时 201 ⇒ **+15**，与新增 `@Test` 数逐值相等）
- `BugInstance size is 0` **×3**（本切片三个模块各一）；`[ERROR]` 行 **0**
- surefire 报告 19 份 `.txt`，mtime **04:26:52–04:26:55**，落在本轮 **[04:23:49, 04:27:27]** 之内 ⇒ 数字出自本轮的干净轮

---

## §一 判据逐条实测值（全部为当轮跑过的用例名 + 行号）

| 判据 | 实测用例（红即杀点） |
|---|---|
| 重 id ⇒ 拒 | `UnitOperationsTest.createChainAppliesTheChainAndRejectsDuplicatesAndDanglingReferences:740`、`UnitCommandHandlersTest.createCommandChainAppliesAndRejectsDuplicatesAndDanglingUnits:873` |
| 成员不存在 ⇒ 拒 | 同上两条（`requireChainMembersResolve`） |
| `commander ∉ members` ⇒ 拒 | **构造期收紧**在 `CommandChain` 的紧凑构造器（`CommandChain.java:40`「commander 必须是 members 之一」，`CommandChainTest` 钉住）；op 层给出可读理由的那条**结构性不可达**（见 §七 T5-L1） |
| ★ **多属不禁** | `UnitOperationsTest.aUnitMayBelongToSeveralChainsAndChainsMayCrossFreely:788` |
| `updateChain` 未给字段不动（**m2 靶子**） | `UnitOperationsTest.updateChainTouchesOnlyTheFieldsThatWereGiven:824`、`UnitCommandHandlersTest.updateCommandChainTouchesOnlyTheGivenFields:954` |
| `updateChain`：给 `members` ⇒ commander 必须 ∈ 新 members；只给 commander ⇒ ∈ 既有 members；全不给 ⇒ 放行 | `UnitOperationsTest.updateChainRejectsCommandersOutsideTheEffectiveMembersAndDanglingReferences:874`、`UnitCommandHandlersTest.updateCommandChainRejectsUnknownChainsAndCommandersOutsideTheEffectiveMembers:1000` |
| ★ **Map 键往返**（**m3 靶子**，§八 #19） | `UnitCodecTest.commandChainIdsSurviveRoundTripAsMapKeysOnOpProducedStates:203`、`UnitCodecTest.snapshotRoundTripsCommandChainsWithASharedMember:170`、`UnitCodecTest.changeSetRoundTripsCommandChainUpserts:185` |
| 链命令的**变更集往返**（铁律 5） | `UnitCommandHandlersTest.chainCommandsProduceChangeSetsThatRebuildTheTarget:1069` |
| `disband` 双向前置（裁定 T5-U2） | `UnitOperationsTest.disbandRejectsUnitsThatAreStillInACommandChain:948`、`disbandRemovesAChainFreeUnitAndKeepsTheChains:981`、`UnitCommandHandlersTest.disbandRejectsChainedUnitsAndKeepsTheChainsForChainFreeOnes:1038` |
| 链**不是层级**（扁平星形、无环可查） | `aUnitMayBelongToSeveralChainsAndChainsMayCrossFreely:788`（多条链交叉不产生环、逐值一致） |
| handler 形状 / 载荷拒绝 | `everyHandlerRejectsMalformedPayload`（含两条新命令） |
| `simos-unit` 计数 | **201 → 216**；前端 **88/88 未重跑**（见 §六） |

---

## §二 T5-U2 逐处修复与逐处守卫（**每一处都有自己的守卫用例，且都真的红了**）

裁定原文：六处 `UnitState(Map<UnitId, Unit>)` 静默抹链，其中 5 处改成 `state.withUnits(next)`；`disband` 另加"先改链、再解散"的双向前置；`DemoWorld` 语义正确、**不动**。

| # | 落点（当前行号） | 守卫用例 | 变异轮红点（实测） |
|---|---|---|---|
| 1 | `UnitOperations.java:204`（`disband` 的返回） | `UnitOperationsTest.disbandRemovesAChainFreeUnitAndKeepsTheChains:989` + `UnitCommandHandlersTest.disbandRejectsChainedUnitsAndKeepsTheChainsForChainFreeOnes:1061` | **t5m06**：两处同时红（`117, Failures: 2`） |
| 2 | `UnitOperations.java:251`（`attachSubtree` 的返回，T3 未收口） | `UnitOperationsTest.formationCommandsKeepTheChains:1005` | **t5m08**：红在 `:1005`（attach 那半） |
| 3 | `UnitOperations.java:325`（`reparentSubtree` 的返回，T4 未收口） | `UnitOperationsTest.formationCommandsKeepTheChains:1009` | **t5m09**：红在 `:1009`（reparent 那半） |
| 4 | `UnitOperations.java:543`（私有助手 `withUnit`——**最宽的一处**，rename/setStrength/placeAt/planRoute/setStatus/cancelRoute/setOffset 七条都经它） | `UnitOperationsTest.everyWithUnitRoutedOperationKeepsTheChains:1022` | **t5m04**：红在 `:1028`（rename 那条断言；七条**全部**会清链，红点取第一条） |
| 5 | `UnitTimeParticipant.java:122`（每次 tick 的返回） | `UnitTimeParticipantTest.advanceKeepsCommandChainsWhilePositionAndMovementChange:183` | **t5m05**：红在 `:196`（`next.commandChains()` 的逐值断言） |
| — | `simos-app/.../demo/DemoWorld.java:126` | **按裁定不动**（该处语义正确；`git diff` 实测 simos-app 0 行） | 不适用 |

**五处的还原变异体都带 ⑩ 道的自证**（装置修正见 §五）：`frag=[…] orig_hits=0 pushed_hits=1` —— 即"变异体里**真的出现了**那个还原片段，而原件里 0 次"。

`disband` 的双向前置实测：消息含**链 id** 与 **「先改链」** 语义（`requireNotInAnyChain`，`UnitOperations.java:208-218`；commander 方向 `:212`、member 方向 `:215`），风格对齐既有的「先改编、再解散」（`:182-183`）——两方向各有用例（`disbandRejectsUnitsThatAreStillInACommandChain:948` 覆盖两向，t5m07 删掉整个前置时 `:954` 与命令边界 `:1045` 同时红）。

**§一.2 不变量 2（引用完整性）**：`disband` 是唯一能制造"链引用不存在单位"的入口 ⇒ 堵在这里即可；`createChain`/`updateChain` 侧由 `requireChainMembersResolve` 保证所有被引单位在同快照内存在。

---

## §三 变异轮总表（**28 轮：26 被杀 / 2 存活**，九道门禁全过）

- 装置：`mutants/mut-round.sh`（白名单 8 个靶文件、按目标类名推送、清陈旧 `.class`/报告、`COMPILATION ERROR` 强制 0、报告 mtime 落本轮、逐字节 `cp` 还原、日志自指、**⑩ T5-U2 还原自证**）；驱动 `mutants/run-all-rounds.sh`
- 每轮的 md5 四元组（orig / baseline / mutant / pushed / restored）**都写在该轮日志的 `===== SELF-REFERENTIAL RECORD =====` 块里**（自指）；下表 md5 列取前 8 位，全值见日志
- 选择器（每轮同）：`UnitOperationsTest,UnitCommandHandlersTest,UnitCodecTest,UnitTimeParticipantTest`
- "再生"列：**靶文件字节变了 ⇒ 必须从新字节再生**（裁定 T5 第 (1) 条）；`复用` = 靶文件字节未变且与 T3/T4 轮**逐字节相同**

| # | 轮 | 靶文件 | 再生 | orig → mutant（8 位） | 红点（实测，被保护断言） | 结论 |
|---|---|---|---|---|---|---|
| 1 | t3m1 | UnitOperations | 再生 | e3731e15 → 73cabcf8 | `UnitOperationsTest.detachTouchesOnlyTheNodeItself:397`、`splitFormationDetachesOnlyTheNamedNodes:577`、`splittingThenMergingRoundTripsTheFormation:708`、`UnitCommandHandlersTest.detachUnitTouchesOnlyTheNode:580`、`splitFormationDetachesTheNamedNodeOnly:728` | KILLED |
| 2 | t3m2 | UnitOperations | 再生 | e3731e15 → 6699ac08 | `attachCascadesAttachedToTheWholeSubtree:353`、`mergeFormationCascadesToTheChildsSubtree:671`、`UnitCommandHandlersTest.attachUnitCascadesToTheWholeSubtree:549` | KILLED |
| 3 | t3m3 | UnitOperations | 再生 | e3731e15 → f98f26cd | `attachRejectsAParentInsideTheSubtree:369`、`mergeFormationRejectsCyclesAndUnknownUnits:683`、`UnitCommandHandlersTest.attachUnitRejectsACycleUnknownUnitsAndAMissingParent:561` | KILLED |
| 4 | t3m4 | UnitOperations | 再生 | e3731e15 → 76d15618 | `anOffsetIsNotRequiredToStayInsideTheMap:445`、`anOffsetShiftsTheEffectivePositionOfAnAttachedChild:426`、`UnitCommandHandlersTest.setFormationOffsetAcceptsAPartialComponent:614`、`setFormationOffsetAppliesAndClears:599` | KILLED |
| 5 | t3m5 | **SetFormationOffsetHandler** | **复用** | 749654ad → 15bc49d4 | `UnitCommandHandlersTest.setFormationOffsetAcceptsAPartialComponent:614` | KILLED |
| 6 | t4m1 | UnitOperations | 再生 | e3731e15 → d0560ffd | `reparentSubtreeRemountsEveryDescendantAtTheSameInstant:513`、`reparentSubtreeWithoutAParentPromotesTheSubtreeToRoot:565`、`UnitCommandHandlersTest.reparentSubtreeRemountsTheWholeSubtree:676` | KILLED |
| 7 | t4m2 | UnitOperations | 再生 | e3731e15 → d4d8eeba | `reparentSubtreeRejectsANewParentInsideTheSubtree:529`、`UnitCommandHandlersTest.reparentSubtreeRejectsCyclesAndUnknownUnits:703` | KILLED |
| 8 | t4m3 | UnitOperations | 再生 | e3731e15 → 824b7365 | `mergeFormationRequiresTheSameHexAndTheMovingStatus:652` + `UnitCommandHandlersTest…:778` | KILLED |
| 9 | t4m4 | UnitOperations | 再生 | e3731e15 → ec26f58c | `mergeFormationRequiresTheSameHexAndTheMovingStatus:641` + `UnitCommandHandlersTest…:795` | KILLED |
| 10 | t4m5 | UnitOperations | 再生 | e3731e15 → d260729b | `splitFormationRejectsTargetsOutsideTheSubtreeAndEmptyLists:604` + `UnitCommandHandlersTest.splitFormationRejectsTargetsOutsideTheSubtree:748` | KILLED |
| 11 | t4m6 | UnitOperations | 再生 | e3731e15 → 10c684e3 | `mergeFormationCascadesToTheChildsSubtree:671`、`mergeFormationRejectsCyclesAndUnknownUnits:683` | KILLED |
| 12 | t4m7 | **ReparentSubtreeHandler** | **复用** | f4b82888 → c99d2fa0 | `UnitCommandHandlersTest.reparentSubtreeWithoutAParentPromotesTheSubtreeToRoot:687`（`reason=字段 parent 必填`） | KILLED |
| 13 | t4m8 | UnitOperations | 再生 | e3731e15 → 8b111c91 | `splitFormationRejectsTargetsOutsideTheSubtreeAndEmptyLists:618` + `UnitCommandHandlersTest…:756` | KILLED |
| 14 | t4m9 | UnitPayloads | 再生 | b56b2460 → 580b7c80 | `UnitCommandHandlersTest.createCommandChainAppliesAndRejectsDuplicatesAndDanglingUnits:934`〔members 形状〕、`everyHandlerRejectsMalformedPayload:650` | KILLED |
| 15 | **t5m01** | UnitOperations | 新 | e3731e15 → faf23d01 | `chainCommandsProduceChangeSetsThatRebuildTheTarget:1074`、`UnitCodecTest.commandChainIdsSurviveRoundTripAsMapKeysOnOpProducedStates:211`、`aUnitMayBelongToSeveralChainsAndChainsMayCrossFreely:791` | KILLED |
| 16 | **t5m02** | UnitOperations | 新 | e3731e15 → 51318e0d | `updateChainTouchesOnlyTheFieldsThatWereGiven:834`、`UnitCommandHandlersTest.updateCommandChainTouchesOnlyTheGivenFields:964` | KILLED |
| 17 | t5m03 | UnitCodec | 新 | ccf5e49d → 0dfed35b | —（`117, Failures: 0`） | **SURVIVED**（见 §四） |
| 18 | **t5m04** | UnitOperations | 新 | e3731e15 → 962ec207 | `everyWithUnitRoutedOperationKeepsTheChains:1028`〔site 4〕；⑩ `frag=[return new UnitState(next);] orig_hits=0 pushed_hits=1` | KILLED |
| 19 | **t5m05** | **UnitTimeParticipant** | 新 | 2797dc06 → 9aa5ae56 | `advanceKeepsCommandChainsWhilePositionAndMovementChange:196`〔site 5〕；⑩ `frag=[UnitState target = new UnitState(units);] orig_hits=0 pushed_hits=1` | KILLED |
| 20 | **t5m06** | UnitOperations | 新 | e3731e15 → 572ce998 | `disbandRemovesAChainFreeUnitAndKeepsTheChains:989`、`UnitCommandHandlersTest.disbandRejectsChainedUnitsAndKeepsTheChainsForChainFreeOnes:1061`〔site 1〕；⑩ 自证过 | KILLED |
| 21 | t5m07 | UnitOperations | 新 | e3731e15 → 1783c010 | `disbandRejectsUnitsThatAreStillInACommandChain:954`、`UnitCommandHandlersTest…:1045`〔disband 前置〕 | KILLED |
| 22 | **t5m08** | UnitOperations | 新 | e3731e15 → d175a1aa | `formationCommandsKeepTheChains:1005`〔site 2〕；⑩ 自证过 | KILLED |
| 23 | **t5m09** | UnitOperations | 新 | e3731e15 → 14767d4c | `formationCommandsKeepTheChains:1009`〔site 3〕；⑩ 自证过 | KILLED |
| 24 | t5m10 | UnitOperations | 新 | e3731e15 → 3f8e3bc9 | `updateChainRejectsCommandersOutsideTheEffectiveMembersAndDanglingReferences:883`、`UnitCommandHandlersTest…:1009` | KILLED |
| 25 | t5m11 | UnitOperations | 新 | e3731e15 → 0b20076c | `createChainAppliesTheChainAndRejectsDuplicatesAndDanglingReferences:751`、`UnitCommandHandlersTest.createCommandChainApplies…:894`〔重 id〕 | KILLED |
| 26 | t5m12 | UnitOperations | 新 | e3731e15 → 2b1bb3bd | `createChainApplies…:765`〔成员不存在〕、`UnitCommandHandlersTest…:909` | KILLED |
| 27 | t5m13 | UnitCodec | 新 | ccf5e49d → 06d86206 | `commandChainIdsSurviveRoundTripAsMapKeysOnOpProducedStates:221`、`snapshotRoundTripsCommandChainsWithASharedMember:176`〔键**值**被改坏〕 | KILLED |
| 28 | t5m14 | UnitCodec | 新 | ccf5e49d → eeea955b | —（`117, Failures: 0`） | **SURVIVED**（见 §四） |

**再生的自证（裁定第 (1)(2) 条）**
- 14 个 T3/T4 变异体里，**12 个必须再生**（靶文件 `UnitOperations.java` md5 `e3731e15…`、`UnitPayloads.java` md5 `b56b2460…` 都因 T5 改动而变），另 **2 个复用**（`SetFormationOffsetHandler.java` `749654ad…`、`ReparentSubtreeHandler.java` `f4b82888…` 字节未变）。
- 复用的"与 T3/T4 轮同一份"证据是 **md5 逐字节相等**（实测）：`t3m5` = T3 轮 `m5_SetFormationOffsetHandler.java` = `15bc49d452c51c6ac15f0eb585333593`；`t4m7` = T4 轮 `m7_ReparentSubtreeHandler.java` = `c99d2fa02e5ea127907e12d0886f0383`。
- 再生的 12 个与 T3/T4 轮**不同**（实测：`t3m1` 73cabcf8 vs a925359c、`t3m2` 6699ac08 vs 5c1824b2、`t3m3` f98f26cd vs e44b8ff9、`t3m4` 76d15618 vs 30932a5c、`t4m1` d0560ffd vs b4fd41a7、`t4m2` d4d8eeba vs 062555a2、`t4m3` 824b7365 vs 980ddd3d、`t4m4` ec26f58c vs edef7b35、`t4m5` d260729b vs fe8d4ce7、`t4m6` 10c684e3 vs 49269a0d、`t4m8` 8b111c91 vs 6e239752、`t4m9` 580b7c80 vs beb75fff）——这正是"靶文件字节变了 ⇒ 不能复用"的形态：旧变异体是**旧 UnitOperations 的整份拷贝 + 老变异**，直接推上去会把 T5 的修复与 T5 的链代码一起回退，红/绿都不再作数。
- 每轮自证（裁定第 (2) 条）：日志自记里 **`orig_md5` = `baseline_md5`**（= 该靶文件在 T5 基线锚定的那份字节 ⇒ 变异体的底座确实是**新**字节）、且 **`mutant_md5` ≠ `orig_md5`**、`restored_md5` = 基线；`diff 原件 变异体` 实测 **1 块**（`t3m2`/`t4m1`/`t4m4`/`t4m5`/`t5m07`/`t5m03` **2 块**、`t5m14` 5 块，块数即该轮的拼接笔数）。
- **九道门禁**：28/28 `verdict=OK`；`compile_errors=0`；每轮 surefire 报告 mtime 落轮内；每轮 `cp` 还原后 `restored = baseline`；每批结束 `CLEAN-WORLD OK`（8 个靶路径逐条与 `baseline-md5.txt` 相等）。

---

## §四 存活项（**两种写法一起出现**，如实报）

### 4.1 `t5m03`（计划里的 **m3 靶子**）—— 存活，且是**等价变异体**

- 变异：删掉 `UnitCodec.java:57`（`CommandChainId` 的 Map 键反序列化器注册）。
- 实测：`Tests run: 117, Failures: 0`（同一选择器下空口全绿），`verdict=OK` ⇒ **门禁不是没跑到**。
- **写法一（"存活"）**：删掉该行后**行为没有改变** ⇒ 判据看不见它。
- **写法二（"判据弱于行为 / 等价"）**：`CommandChainId` 是**单 String 的 record** 且带 `static parse`、**无 `@JsonCreator`** ⇒ Jackson 的默认 Map 键路径本来就能建它；显式 `addKeyDeserializer` 对它**不承重**。
- ★ **不许只报存活**：为区分"判据弱"与"变异等价"，补了 **t5m13**（把键**值**改坏：`new CommandChainId("k-" + text)`）⇒ **KILLED**，红点 `UnitCodecTest.commandChainIdsSurviveRoundTripAsMapKeysOnOpProducedStates:221` + `snapshotRoundTripsCommandChainsWithASharedMember:176` ⇒ **Map 键这条判据本身有判别力**，t5m03 的存活是**变异等价**，不是护栏失效。

### 4.2 `t5m14` —— 存活：**整个 `keyModule()` 注册都不承重**

- 变异：删掉 **整个** `keyModule()`（`UnitId` 与 `CommandChainId` 两个注册 + 私有助手 + 5 个随之未用的 import），实测 `Tests run: 117, Failures: 0`。
- **写法一（"存活"）**：整块注册对这两条 ID 的往返**不承重**。
- **写法二（"判据弱于行为 / 设计缺口"）**：`UnitCodec` 的类注写着"照裁定 16 在本模块注册键反序列化器"（spec §五.3 :387 也是这么要求的），但**实测证明它对这两个单 String record 是装饰**——注册与不注册，codec 往返都过。这与 A3-m4（sd 侧同款结论）**同型**。
- ⇒ 结论：**不删**这段注册（spec 点名要求、且换一个**带 `@JsonCreator`/复合结构**的 ID 时它会是承重的），但把它记为**"判据覆盖不到的风险点"**：若有谁把它删了，现有用例**不会响**。

---

## §五 装置的三处修正（都是"先怀疑自己的读取/装置，别先下结论"）

1. **★ 第 19 轮 `t5m05` 被 ⑩ 道当场作废（日志 0 字节、Maven 未跑）**：⑩ 原判据数的是整份文件的 `new UnitState(` —— 对 `UnitOperations.java` 成立（原件 0 次），但 site 5 的靶文件里 **T5 自己的注释合法地写着**"（旧写法 new UnitState(units)" ⇒ 原件计数 = 1，门禁把本轮作废。修法：判据改成**每条还原体各自的、只可能出现在代码里的片段**（`return new UnitState(next);` / `UnitState target = new UnitState(units);`）。**五个 site 的九轮（18..23）在修正后的装置下全部重跑**，记录里带 `frag=…` 字段。**没有为了让某轮好看而放宽判据**——作废轮如实留档（`rounds-summary.txt:56` 的 `ABORT` 行）。
2. **`surefire_summary` 会抽到"恰好没红的那个类"**：逐类报告取 `tail -1` 时，唯一红的 `UnitOperationsTest` 被 `UnitTimeParticipantTest` 的 `10, Failures: 0` 盖住 ⇒ 装置新增**模块级** `module_summary`（`[ERROR] Tests run: 117, Failures: 1`），`t5m04` 因此重跑一遍以统一日志格式。
3. **一条"看着像没杀"的假红**：`t5m03` 首轮 `VOID(无 surefire 汇总行)` —— 删掉 `:57` 后 `import CommandChainId;` 变成未用，**Checkstyle 的 UnusedImports 在 `test` 阶段、surefire 之前**就把构建拦了 ⇒ 没有报告。修法是**同一意图**下再拼一笔删掉那个 import（不造悬空 import），装置**拒绝**把"没有报告"当成"杀"。

另外两处**读取侧**的自证纪律：`md5` 判字节（不判 mtime）、每轮日志自指（四个 md5 + 门禁读数 + 失败清单原文都在日志内），核红点一律读**本轮的** `SELF-REFERENTIAL RECORD` 块。

---

## §六 我未能核实的 / 没做的（诚实清单）

1. **前端 88/88 未在本轮重跑**：`simos-app` 不在允许的 reactor 切片（`-pl simos-unit -am`）里，而全量 `clean verify` 归控制器合并时跑。能证的只有**结构性未动**：`git diff --stat 3e7d33e -- simos-app` = **空**（一行未改）⇒ 前端 88/88 与 T4 关账时同值；**"重跑过一遍"这件事我没做**。
2. **全量 `clean verify` 没跑**（按裁定：最多 `-pl simos-unit -am verify`）；**`install` 一次没跑**，`~/.m2` 未被本次改动污染。
3. **`simos-app` 侧未接线**：`git grep unit.CreateCommandChain -- simos-app` = **空** ⇒ 两条新命令**尚未注册进 Shell**，端到端冒烟是 T9/T10 的活（本任务不碰 app）。
4. **`~/.m2` 的探针构件前提未复核**：本轮 Maven 一律带 `-am`、未跑 `install`，但我**没有**去验 `~/.m2` 里 simos 构件的来源（那是前序任务的结论，本任务只保证不依赖它）。
5. **判据 #19 的"解码期抛"未被独立复现**：t5m03（删注册）实测**不抛** ⇒ spec §五.3 :387 关于"否则解码期抛"的表述**在本模块的这两个 ID 上不成立**（见 §四 4.1）；我没有再去构造一个"复合结构的 ID"来复现它。
6. **未测 `disband` 与链的并发/多链交叉压力**（单线程纯函数，本任务范围内无并发面）。
7. **未测 `updateChain` 把 `members` 缩到只剩 commander 的边界在**命令边界**上的一致性**（域层有用例、payload 层走 `optionalTextArray` 的空数组分支；空数组 ⇒ `members` 空 ⇒ 构造期拒，**这条我没写专门用例**）。
8. **行号引用**：本报告所有行号都是**当轮直读**（`grep -n`/`sed -n`）的，不是凭记忆；但后续任务再改这两个文件时行号会漂。

---

## §七 我认为计划 / spec 本身的缺口（如实报，含待控制器裁的条目）

| 编号 | 事项 | 现状与建议 |
|---|---|---|
| **T5-L1** | 计划 `:352` 写 `createChain(state, chain, at)`，**实现签名没有 `at`**：`createChain(UnitState state, CommandChain chain)` | 理由：`CommandChain` 四个组件**全是非时间序列**（id/name/commander/members 都是标量），没有"在某刻写一段"的对象 ⇒ `at` 无处可用。计划 `:352` 的括注与 §五.2 命令表（`:366` 的载荷 `chainId, name, commander, members[]`，**也没有时刻**）一致 ⇒ **按命令表实现**。**待控制器裁**：是否回填计划文本。 |
| **T5-L2** | 计划 `:352` 要求 op 层对 `commander∉members` "先给可读理由" | **结构性不可达**：`CommandChain` 的紧凑构造器（`:40`）已经拒了，op 层拿到的一定是合法链 ⇒ 该分支写不出来。现状：域层的可读理由**钉在 `CommandChain` 构造期**（`CommandChainTest` 自证），op 层只判"重 id"与"引用不存在"。**待控制器裁**：接受现状并在计划里注明，或要求在 op 层手写一条不可达的防御分支（不建议——会有不可测的死代码）。 |
| **T5-L3** | spec §八 #19 / §五.3 :387 说"不注册键反序列化器 ⇒ 解码期抛" | **实测不成立**（§四 4.1：对单 String record 不承重）。建议 spec 把该条改成"**若 ID 是复合结构/带 `@JsonCreator` 才承重**"，或补一句"单 String 的 ID 走 Jackson 默认键路径"——否则后来者会照抄一个**杀不掉的护栏**当判据。 |
| **T5-L4** | `UnitState` 的 1 参兼容构造器**仍留在生产代码里**（`UnitState.java:57-59`） | 它是 T5-U2 的**全部六处事故的共同根因**：新代码只要写一次 `new UnitState(units)` 就静默抹链，而**编译期与门禁都不响**（t5m04/05/06/08/09 证明：只有专门的守卫用例能红）。本轮按裁定只改调用点。**待控制器裁**：是否在 T6+ 里删掉该构造器（或加 `@Deprecated` + 让它在 `commandChains` 非空时抛）——`DemoWorld.java:126` 与 `simos-app` 的 6 处测试夹具也在用它。 |
| **T5-L5** | `UnitTimeParticipant` 的 T5 注释里出现了 `new UnitState(units)` 字样 | 它让"整份文件计数"型的判据**误报**（§五 1）。已把装置改成片段判据；但**注释里复述被禁止的代码**这个习惯本身值得在 T6+ 定个口径。 |
| **T5-L6** | 载荷层 vs 域层"同一字段名都会拒"的判别力（T4 已记的同族清扫项） | 本轮的 `everyHandlerRejectsMalformedPayload` 覆盖了两条新命令（t4m9 在 `:650` 杀过），但**仍是 token 级**断言；归 T10 一并清扫（与 T4 台账同条）。 |

---

## §八 结论

- T5 六步全部落地：`createChain` / `updateChain` / 两个 handler + `optionalTextArray` / Map 键往返 / `disband` 链前置 / 扁平星形无环用例。
- **T5-U2 五处修复逐处有守卫、逐处被对应变异体红**（§二 表格，红点行号均为实测）。
- 变异：**28 轮 / 26 杀 / 2 存活**，九道门禁 28/28 通过；存活两项**同时**给出了"存活"与"判据 vs 行为"两种写法（§四）。
- 门禁：`./mvnw -o -pl simos-unit -am verify` **rc=0**，`simos-unit` **201 → 216**（+15 = 新增 `@Test` 数），报告 mtime 落本轮；`simos-app` 及前端**一行未动**。
