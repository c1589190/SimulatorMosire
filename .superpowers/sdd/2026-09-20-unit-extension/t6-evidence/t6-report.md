# T6 报告 — 稀疏路线 `unit.PlanSparseRoute`（E2/P12）

- 分支 `ue/t6`（worktree `.claude/worktrees/uet6`），基线 `e2dee0f`（T5 收口）
- 实现提交 **`0d40cdd`** — `unit-ext T6：稀疏路线 unit.PlanSparseRoute（A* 逐段展开；不可达命令期拒；注入裁定 U3）`
- 裁定 **U3**（控制器已裁、未再议）：handler **构造器注入** `MovementCost`；`map` 读 `state.module("map")`，**形制照 `UnitTimeParticipant.mapOf`**（装配故障当场炸、**不静默兜底**）

---

## 一、落地

| 文件 | 性质 | 行数 |
|---|---|---|
| `simos-unit/src/main/java/io/mosire/simos/unit/spi/PlanSparseRouteHandler.java` | **新增** | 80 |
| `simos-unit/src/main/java/io/mosire/simos/unit/ops/UnitOperations.java` | 改（纯函数 `expandSparsePath` + `planSparseRoute` 复用既有 `planRoute`） | +48 |
| `simos-unit/src/test/java/io/mosire/simos/unit/ops/UnitOperationsTest.java` | 改（**纯追加**） | +193（7 条） |
| `simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java` | 改（**纯追加**） | +275（10 条） |
| 合计 | | **+596 / −0** |

- `git show --stat 0d40cdd` = 上述四个文件；`git diff --stat e2dee0f..HEAD -- simos-app` **无输出**（`simos-app` 一字未动）。
- **`UnitPayloads.java` 未改**（先核过：`requireWaypoints`（`:209`）现成可用——它只做"必须是 `[{q,r}…]` 数组 + 逐项 `hexFrom`"，**不含**条数约束；`waypoints:[]` 的"至少两个"由 `Route` 构造器给出，`planSparseRoute` 路径上照样落得到，故不需要**加**守卫）。
- ★ **T5-L4 遵守**：本轮**没有**任何 `new UnitState(...)`——`planSparseRoute` 末尾调既有 `planRoute`，后者走 `withUnit` → `state.withUnits(next)`。

### 门禁（模块级，前台，显式 timeout）

```
./mvnw -o -pl simos-unit -am verify      # 前台，timeout=600000
```

| 轮 | 日志 | rc | 结果 |
|---|---|---|---|
| 基线（T5 收口后） | `logs/baseline-verify.log` + `baseline-verify-rc.txt` | **0** | `simos-unit` **216** |
| 实现轮（首） | `logs/gate-impl-attempt1-red.log`（留档不删） | 非 0 | 装配故障用例的夹具写错（见 §五 (f)） |
| 实现轮 | `logs/gate-impl.log` + `gate-impl-rc.txt` | **0** | `simos-unit` **233**、BUILD SUCCESS |
| **收口轮**（`rm -rf simos-unit/target` 后重跑） | `logs/gate-final.log` | **0** | 170 / 362 / **233**，`BUILD SUCCESS` |

**计数：`simos-unit` 216 → 233（+17 = ops 7 + SPI 10）**，delta 干净（util/map 两模块数字与基线逐值相同：170 / 362）。
★ 全量 `clean verify` **不是本轮范围**（控制器合并轮做）。

---

## 二、判据逐条实测值

| # | 判据 | 用例 | 实测值 |
|---|---|---|---|
| **m1** | 非相邻 `waypoints` ⇒ 逐 hex `path`，`Route` 构造通过 | `UnitOperationsTest.planSparseRouteFreezesTheExpandedPath:1107`；`UnitCommandHandlersTest.planSparseRouteExpandsNonAdjacentWaypointsIntoAPerHexPath:1186` | `waypoints=[H11(1,1), H13(1,3)]` ⇒ `route.path()` **恰好** `[H11, H12(1,2), H13]`（`containsExactly`），`waypoints()` 原样保留（仍是稀疏两点），`departedAt()==T10` |
| **m2** | 任一相邻段不可达 ⇒ **命令期拒** | `expandSparsePathRejectsAnUnreachableSegment:1139`（ops 抛 `IllegalArgumentException`）；`planSparseRouteRejectsAnUnreachableSegment:1213`（SPI 折成 `Rejected`） | 三种情形全部 `reason` **含「不可达」**：① 图上孤岛 `(5,5)`；② 前段可达、**后段**不可达 `(1,1)→(1,3)→(5,5)`；③ 根本不在图上 `(9,9)`。拒绝后 `unitSlice(...).movement()` **empty**（状态不动） |
| **m3** | 起点 ≠ `effectivePosition` ⇒ 拒 | `planSparseRouteRequiresAStartThatMatchesTheEffectivePosition:1173`；`planSparseRouteRejectsStartThatIsNotTheEffectivePosition:1257` | `waypoints=[(1,2),(1,3)]` 而单位在 `(1,1)` ⇒ ops 抛、SPI `reason` **含「起点」**；另附未知 id ⇒ `Rejected("单位不存在")`（`planSparseRouteRejectsUnknownIdAndMalformedPayload:1309`） |
| **Route 约束** | 展开后的 `path` 满足 `Route` **全部**约束 | 由 `Route` 构造期强制 + `expandSparsePathJoinsSegmentsWithoutRepeatingTheJoint:1119` | 尺寸/首尾/子序列/相邻四条由构造器守；★ 拼接处**不去重**：两段 `[H11,H12,H13]+[H13,H12]` 的原始拼接就是 `[H11,H12,H13,H12]`（**逐值钉住**，即"接缝只出现一次、回头造成的重复**留给 `Route` 拒**"） |
| **R4** | 跨段重复 hex ⇒ **命令期拒**（由 `Route` 构造器抛、handler 折成 `Rejected`） | `planSparseRouteRejectsACrossSegmentRepeat:1204`（ops）；`UnitCommandHandlersTest.planSparseRouteRejectsACrossSegmentRepeat:1246` | `waypoints=[H11,H13,H11]` ⇒ 拼接 `[H11,H12,H13,H12,H11]`：**两段各自都是简单路径**，尺寸/首尾/子序列/相邻四条**全部通过**，只有重复检查响 ⇒ `reason` **含「重复」** |
| U3 注入 | handler **真的用了注入的** `MovementCost` | `planSparseRouteUsesTheInjectedMovementCost:1291` | 同一载荷、同一地图：地形成本 ⇒ `Applied`（前提断言 `movement().isPresent()`）；换成**封掉 H12→H13 的替身** ⇒ `Rejected("…不可达…")` |
| U3 mapOf | 缺切片 / 类型不符 ⇒ **装配故障**（`IllegalStateException`），**不折成拒** | `planSparseRouteBlowsUpWhenTheMapSliceIsMissing:1330`、`…IsNotAMapSnapshot:1356` | 两条都 `isInstanceOf(IllegalStateException)` + `hasMessageContaining("map")` / `("MapSnapshot")` |
| 等价 | 逐格相邻的 `waypoints` ⇒ 与既有 `unit.PlanRoute` **逐值同路** | `sparseExpansionMatchesPlanRouteForAdjacentWaypoints:1220`（ops）；`planSparseRouteMatchesPlanRouteForAdjacentWaypoints:1268`（SPI） | 同载荷下两者的 `Movement` 值 `isEqualTo`（复用既有 `planRoute` 的直接后果） |
| **T5-L4** | 新路径不得清空 `commandChains` | `planSparseRouteKeepsTheCommandChains:1237` | `next.commandChains()` **逐值等于** `base.commandChains()`，且 `base` 未被改动（纯函数） |
| 命令名 | `type()` 与 spec 表一致 | `planSparseRouteTypeNameMatchesTheSpecTable:1176` | `"unit.PlanSparseRoute"` |
| 载荷 | 未知 id / `waypoints` 非数组 / **空数组** / 非 JSON | `planSparseRouteRejectsUnknownIdAndMalformedPayload:1309` | 分别含「单位不存在」/「waypoints」/「至少两个」（★ **来自 `Route` 构造器**，不是 `requireWaypoints`）/「不是合法 JSON」 |

---

## 三、变异轮（8 轮，十道门禁，一次一个 Maven，前台）

装置 `mutants/mut-round.sh`（白名单=两个靶文件；选择器 `UnitOperationsTest,UnitCommandHandlersTest,UnitCodecTest`）；生成器 `mutants/make-mutants.py`（每条 `old` **必须恰好出现 1 次**，否则当场 `ABORT`）；逐轮自记见 `mutants/rounds-summary.txt` 与各 `t6m*.log` 的 `SELF-REFERENTIAL RECORD` 块。

**八轮全部 verdict=OK、mvn_rc=1、outcome=RED(被杀)；存活 0 条。**

| 轮 | 变异（靶） | ⑩ 自证片段 / 方向 | 杀它的红点（原文） |
|---|---|---|---|
| t6m1 | 去掉 A* 展开 ⇒ `new Route(waypoints, waypoints)`（**计划 m1**） | `new Route(waypoints, waypoints)` revert：orig **0** / pushed **1** | 7 条：`planSparseRouteFreezesTheExpandedPath:1109 » IllegalArgument path 相邻格必须相邻：1_1 → 1_3`、`planSparseRouteExpandsNonAdjacentWaypointsIntoAPerHexPath:1188`、`planSparseRouteRejectsACrossSegmentRepeat`（ops+SPI）、`planSparseRouteRejectsAnUnreachableSegment:1221`、`planSparseRouteUsesTheInjectedMovementCost:1294`、`planSparseRouteKeepsTheCommandChains:1240` |
| t6m2 | 段不可达时**静默截断** `.orElse(List.of())`（**计划 m2**） | `.orElse(List.of())` revert：0 / 1 | 3 条：`expandSparsePathRejectsAnUnreachableSegment:1140`、`planSparseRouteRejectsAnUnreachableSegment:1221 [在图上但不可达（孤岛）]`、`planSparseRouteUsesTheInjectedMovementCost:1304 [注入封掉 H12→H13 的替身 ⇒ 同一载荷变不可达]` |
| t6m3 | 删掉**起点校验**（`planRoute` 里既有那一处，**计划 m3**） | `if (!start.equals(routeStart)) {` **delete**：orig **1** / pushed **0** | 4 条：`UnitOperationsTest.planRouteRequiresAStartThatMatchesTheEffectivePosition:214`（**既有**）、`planSparseRouteRequiresAStartThatMatchesTheEffectivePosition:1174`、`UnitCommandHandlersTest.planRouteRejectsStartThatIsNotTheEffectivePosition:504`（**既有**）、`planSparseRouteRejectsStartThatIsNotTheEffectivePosition:1259` |
| t6m4 | 跨段重复时**顺手去重**（R4 的静默变体） | `List.copyOf(new LinkedHashSet<>(path))` revert：0 / 1 | 3 条：`expandSparsePathJoinsSegmentsWithoutRepeatingTheJoint:1129`、`planSparseRouteRejectsACrossSegmentRepeat:1215`（ops）、`…:1252`（SPI）——★ 去重后 `Route` **仍会拒**，但理由从「重复」变成「首尾」 |
| t6m5 | handler **不用注入的**成本，写死 `TerrainMovementCost.INSTANCE`（**U3 的靶子**） | `waypoints, TerrainMovementCost.INSTANCE, at` revert：0 / 1 | 1 条，**恰是** `planSparseRouteUsesTheInjectedMovementCost:1298` |
| t6m6 | `mapOf` **静默兜底**（缺切片/类型不符 ⇒ `null`，不炸）——**U3「不许静默兜底」的靶子** | `state.module("map").orElse(null)` revert：0 / 1 | 2 条：`planSparseRouteBlowsUpWhenTheMapSliceIsMissing:1337`、`…IsNotAMapSnapshot:1369` |
| t6m7 | `withUnit` 回到 1 参兼容构造器 `new UnitState(next)`（**T5-L4 靶子**，本轮新路径也走这里） | `return new UnitState(next);` revert：0 / 1 | 2 条：`UnitOperationsTest.everyWithUnitRoutedOperationKeepsTheChains:1034 [rename]`（**既有**）、`planSparseRouteKeepsTheCommandChains:1245 [★ T5-L4：链逐值活下来]` |
| t6m8 | `type()` 返回 `unit.PlanRoute`（错名；T9 按 type 注册） | `return "unit.PlanRoute";` revert：0 / 1 | 1 条：`planSparseRouteTypeNameMatchesTheSpecTable:1176` |

- 每轮 `restored_md5 == baseline_md5`（源树逐字节还原，`cp` 还原而非 `git checkout --`）；`compile_errors=0`；surefire 报告落在本轮内。
- 收口后从仓根复跑 `md5sum -c mutants/baseline-md5.txt`：两份靶文件 **OK**；源树内**零**个规范名之外的 `.java`。

### ★ 作废轮（留档不删）

`t6m6` **首轮作废**：变异体把 `Snapshot` 变成未用 import ⇒ **Checkstyle `UnusedImports`** ⇒ 构建**没跑到 surefire** ⇒ 门禁 ⑥ 判 `VOID`。
按规则：日志留档 `mutants/t6m6.log.VOID-1`、根因记在 `t6m6.log.VOID-1.note`；改写变异体为**仍使用 `Snapshot`** 的形态（`orElse(null)` + 三目），⑩ 道自证片段随之改为 `state.module("map").orElse(null)`（orig 0 / pushed 1），**前台重跑 ⇒ RED(被杀)**。
⇒ 本轮**不是**"没红"，是**"没跑到"**；上面的表里记的是**重跑后**的结论。

---

## 四、判据覆盖与判别力的边界（不是存活，是"别把它当护栏"）

1. `sparseExpansionMatchesPlanRouteForAdjacentWaypoints`（ops + SPI）**无独立判别力**——它在 t6m1 下也红，但只因为 m1 会把相邻路点也弄坏；作为"与 `PlanRoute` 等价"的证据它成立，作为**变异杀手**它不额外贡献（等价子域）。**不拿它充当护栏。**
2. m4 的红**部分依赖理由文本**（「重复」vs「首尾」）：一个只断言 `Rejected` 的写法会放过 m4 的"去重"变体（去重后 `Route` 照样拒）。本轮**同时**有 `expandSparsePathJoinsSegmentsWithoutRepeatingTheJoint`（判**逐值拼接**）兜底，故 m4 不靠单一措辞。
3. m3 的杀点与**既有** `PlanRoute` 守卫**共享**（起点校验在既有代码里，本轮**没有**复制第二份）——故 m3 的红点里有两条是 T5 之前就在的用例。
4. `mapOf` 的 `instanceof` 分支**只能靠手造替身够到**（`SimulationState` 构造期要求"键 == 快照 `namespace()`"，而 `map` 命名空间的正主只有 `MapSnapshot`）⇒ 测试里的 `ImpostorSnapshot` 是**必要装置**，该分支的真实发生率**未验**。

---

## 五、§诚实清单（没做 / 没验 / 存活者）

**存活者：无**（8 轮 8 被杀）。以下是**没做、没验**的部分：

- **(a)** `revisions` **行数不变**这条判据**在 `simos-unit` 里够不到**：模块边界只能量到"handler 返回 `Rejected` ⇒ 不产出 `UnitChangeSet`"，而 `revisions` 表在 `simos-core`。⇒ 端到端的"拒了就不落行"归 **T9/T10**（T9 才把 handler 注册进 `Shell`）。本轮的证据是**机制级**的：`planSparseRouteRejectsAnUnreachableSegment:1236` 断"状态不动"。
- **(b)** `PlanSparseRouteHandler` **未注册**（注册是 T9 的活）⇒ 本模块**没有**端到端冒烟；全部 SPI 证据走 `SpiFixture` 的测试内世界。
- **(c)** 全部夹具是**合成小图**（3 格 `H11/H12/H13` + 孤岛 `(5,5)`）——**没有**在真档（19441 格）上跑过稀疏路线。
- **(d)** A* 在大图上的**代价与决定论**未测（M3 起就挂着的既有开口项，本轮未消）。
- **(e)** `type()` 与 spec 表的**逐字**一致只钉了 `unit.PlanSparseRoute` 一条（与既有那 16 条同制）；"注册后命令名可用"要等 T9。
- **(f)** 实现轮**首跑红**、已留档 `logs/gate-impl-attempt1-red.log`：装配故障用例最初用 `SpiFixture.singleModuleState("map", unitSnapshot)`，被 `SimulationState` 构造期拒（"键必须等于 `namespace()`"）。修法是新增 `ImpostorSnapshot` **并把两个切片都放进去**（否则先炸的是"没有 unit 切片"——`UnitSnapshots.of` 在 `mapOf` **之前**调用，这条顺序本身也是实测到的）。★ 这轮**不算变异轮**，是普通红灯。
- **(g)** 前端/浏览器 e2e **与本轮无关**（零前端改动）；social/core/sd/app 四个模块**模块级门禁没跑到**（`-pl simos-unit -am` 只含 util/map/unit）。
- **(h)** `requireWaypoints` 的**条数**约束**不存在**——"waypoints 至少两个"由 `Route` 构造器给（`planSparseRouteRejectsUnknownIdAndMalformedPayload:1318` 断「至少两个」即此）。ops 层的 `expandSparsePath` 自身**不判** `waypoints.size()>=2`（单点 ⇒ 返回空 list，随后被 `Route` 拒）。⇒ **守卫在 `Route` 与载荷层，不在纯函数**；若将来有人**单独**调 `expandSparsePath`，得自己保证 ≥2。

---

## 六、待控制器裁的缺口（gap table）

| # | 缺口 | 现状 | 建议 |
|---|---|---|---|
| G1 | **spec §二.2 的文字与裁定 U3 冲突**：spec 的散文说 handler 用 `TerrainMovementCost.INSTANCE` 调 `PathFinder`，而 U3 定为**构造器注入** `MovementCost` | 实现按 **U3**（注入）；spec **原文未动**（按纪律：不改 spec/计划，只登记） | 由控制器裁：是回填 spec §二.2 的措辞，还是记成"U3 取代 spec 文本" |
| G2 | 「`revisions` 行数不变」（判据 m2 的一半）**在模块内不可测** | 只证到"`Rejected` ⇒ 无 `UnitChangeSet`"（机制级） | 判据的端到端形态归 **T9/T10**（注册后经 `CommandBus`） |
| G3 | 计划 T6 段列的落地清单与实现有**一处出入**：计划要求"先核 `requireWaypoints` 能否复用，能就别动" —— 核实结论是**能复用**（它不含条数约束，"至少两个"由 `Route` 给），故 `UnitPayloads.java` **一行未改** | 已按符号（而非计划行号）执行 | 若控制器要的是"载荷层也判 ≥2"，那是**新增守卫**（须自带变异轮，裁定 42）；本轮**没做**，因为 `Route` 已经兜住 |
| G4 | R4 的判据**与 `Route` 的措辞耦合**：断言是"理由含『重复』" | 若 `Route` 的重复检查改措辞，两条 R4 用例会红（**这是特性不是缺陷**——它保证拒的理由真的是重复那条） | 无需动作，登记备查 |
| G5 | `mapOf` 的 `instanceof` 分支**没有真实触发路径**（只有测试替身够得到） | 已用 `ImpostorSnapshot` 证过；真实发生率未验 | 归 T9/T10 观察（若真世界里 `map` 切片永远只可能是 `MapSnapshot`，该分支即防御性代码） |

---

## 七、交付物清单（本目录）

```
t6-evidence/
  t6-report.md                      ← 本文件
  logs/baseline-verify.log          基线（216）
  logs/baseline-verify-rc.txt       rc=0
  logs/gate-impl-attempt1-red.log   实现轮首跑红（留档）
  logs/gate-impl.log / gate-impl-rc.txt   实现轮（233，rc=0）
  logs/gate-final.log               收口轮（清 target 后，233，rc=0 / BUILD SUCCESS）
  mutants/make-mutants.py           变异体生成器（old 必须恰好 1 次 + ⑩ 道逐片自证）
  mutants/manifest.txt              8 条的 label/target/frag/dir/expect/orig_md5/mutant_md5
  mutants/baseline-md5.txt          两个靶文件的基线 md5
  mutants/mut-round.sh              十道门禁装置
  mutants/run-all-rounds.sh         串行跑 8 轮
  mutants/rounds-summary.txt        8 轮的逐轮自记摘要（RED ×8）
  mutants/t6m1..t6m8_*.java         变异体（8 份）
  mutants/t6m1..t6m8.log            各轮日志（含 SELF-REFERENTIAL RECORD 块）
  mutants/t6m6.log.VOID-1 (+.note)  ★ 作废轮留档 + 根因
```
