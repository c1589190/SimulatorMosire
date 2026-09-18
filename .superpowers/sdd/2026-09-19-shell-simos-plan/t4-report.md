# M5 T4 报告：unit 命令面补齐（7 个 handler）

日期：2026-09-19　分支：`m5/t4`（worktree `.claude/worktrees/m5t4`，基线 `be1dad6`）
执行者：T4 实现者 agent
依据：spec `2026-09-19-shell-simos-design.md` §四 + §〇.4；计划 `2026-09-19-shell-simos-plan.md` T4

## 〇 交付物清单

**main 源码**（均在 `simos-unit/src/main/java/io/mosire/simos/unit/spi/`）：

| 文件 | 内容 |
|---|---|
| `UnitPayloads.java` | 共享载荷解析助手：`parse` / `requireText` / `optionalText` / `optionalId` / `requireInt` / `requireHex` / `optionalHex` / `requireEquipment` / `requireWaypoints`；坏载荷一律抛 `IllegalArgumentException`（可读中文原因），handler 折成 `Rejected` |
| `CreateUnitHandler.java` | `unit.CreateUnit`；组 `Unit` + 初始 `parent`/`position` 段落在 `state.meta().timestamp()` |
| `ReparentUnitHandler.java` | `unit.ReparentUnit`；`parent` 缺失/null = 清根 |
| `SetStrengthHandler.java` | `unit.SetStrength`；`equipment` 整份替换 |
| `PlaceAtHandler.java` | `unit.PlaceAt`；`hex` 缺失/null = 撤销位置（并清在途路线，M3 语义） |
| `PlanRouteHandler.java` | `unit.PlanRoute`；起点须等于 `effectivePosition(at)` |
| `CancelRouteHandler.java` | `unit.CancelRoute`；清 `Movement` |
| `DisbandUnitHandler.java` | `unit.DisbandUnit`；`at` 时刻有下属 ⇒ 拒绝 |

全部与 `RenameUnitHandler` 同形：`UnitSnapshots.of(state)` 在 try 外（装配故障当场炸）、自反序列化 → `UnitOperations.*` →
`Applied(UnitChangeSet.between(before, after))`；`IllegalArgumentException`（含领域类型抛的）→ `Rejected(e.getMessage())`。

**测试**：`simos-unit/src/test/java/io/mosire/simos/unit/spi/UnitCommandHandlersTest.java`（29 条，复用 `SpiFixture`）。

**证据**：`t4-evidence/`（定向绿轮、全量 verify 绿轮、变异轮 m1/m2、装置脚本 + 变异体 + 原件快照）。

## 一 实测数字（照抄日志）

**定向测试**（`logs/green-targeted.log`）：

```
Tests run: 29, Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.unit.spi.UnitCommandHandlersTest
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.unit.spi.RenameUnitHandlerTest
Tests run: 9,  Failures: 0, Errors: 0, Skipped: 0 -- in io.mosire.simos.unit.spi.UnitTimeParticipantTest
Tests run: 47, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**全量 `./mvnw clean verify`**（`logs/full-verify.log`）：`rc=0`；reactor 6 项（父 POM + Util/Map/Social/Unit/Core 五模块）
全部 SUCCESS；`BugInstance size is 0` ×5；`[ERROR]` 0 行。

| 模块 | 基线（M4 终态） | 本任务 | 差 |
|---|---|---|---|
| util | 170 | 170 | 0 |
| map | 255 | 255 | 0 |
| social | 37 | 37 | 0 |
| **unit** | **93** | **122** | **+29** |
| core | 145 | 145 | 0 |
| **合计** | **700** | **729** | **+29** |

`+29` = `UnitCommandHandlersTest` 的 29 条，**只有 unit 长**（前 4 个与 core 一个没动）。

## 二 变异自证（九道门禁；装置 `t4-evidence/mutants/mut-round.sh`，根目录 = worktree）

每轮：干净世界 md5 → 变异体字节不同 → 删陈旧 `.class` → `COMPILATION ERROR`=0 且 `Tests run:`≥1 → surefire 报告 mtime 落在本轮 →
红点核对 → `cp` 逐字节还原 → 三处 md5 **追加进日志本身**。

| m | 变异（目标类） | orig_md5 → mutant_md5 | 红在哪（照抄日志） | 是否落在被保护断言 |
|---|---|---|---|---|
| m1 | `SetStrengthHandler`：忽略载荷 `equipment`，保留旧表 | `e9abd7bc…a417` → `a5ddac17…afe0` | `setStrengthReplacesMemberAndEquipment:256`；`Expecting actual: {"步枪"=50} to contain only following keys: ["炮"] keys not found: ["炮"]`；`Tests run: 29, Failures: 1` | **是**——红在"equipment 整份替换"的逐值断言 |
| m2 | `CreateUnitHandler`：`at = SimosTimestamp.of(0)` 而非 base 时间戳 | `3ff393f7…7f89` → `48d747b6…32cf` | `createUnitAppliesEveryFieldWithInitialSegmentsAtBaseTimestamp:153`；`expected: SimosTimestamp[tick=5,…] but was: SimosTimestamp[tick=0,…]`；`Tests run: 29, Failures: 1` | **是**——红在"初始段时刻 = base 时间戳"的逐值断言 |

两轮 `COMPILATION_ERROR_lines=0`、`worktree_restored == orig_md5`（逐字节还原，未用 `git checkout --`）。每轮只有 1 条红，其余 28 条绿。
m1 的变异体仍调用 `requireEquipment` 做形状校验，故坏载荷/查无此人/负人数三条拒绝路径行为不变——**只破坏"整份替换"这一条**。

**m2 可判别性的前提**：测试在**非零 base 时间戳**（T5）上跑 `CreateUnit`；`SpiFixture.state` 把时间戳写死 T0，故测试自带
`worldAt(at, base)` 构造同刻切片。若 base 时间戳是 T0，m2 与原件输出逐字节相同（假绿）——这是选 T5 的理由。

## 三 偏离 / 取代说明候选

1. **`unit.PlanRoute` 载荷无法表达"waypoints 少、path 多"**（真设计缺口，非笔误）：spec §四载荷只给
   `waypoints[{q,r}…]`，而 M3 `Route(waypoints, path)` 允许 waypoints 是 path 的稀疏子序列（如
   `waypoints=[H11,H13]`、`path=[H11,H12,H13]`）。handler 采用**唯一可重建口径**：`new Route(waypoints, waypoints)`
   ⇒ 要求点列本身逐格相邻。⇒ 若 GUI/MCP 需要稀疏路径，须**先补载荷字段**（如 `path` 或自动 A\*）。记入取代说明候选，
   **不在此任务裁决**。
2. **`UnitPayloads` 为包私有 final 类**（非 public）：与既有 `UnitSnapshots` 同形，只服务本包 handler；不新增公共 API。
3. **助手只做形状/类型校验，范围交给领域类型**：`member ≥ 0`、`speed ≥ 1`、`mobilityPerMille ≥ 1`、装备值 `≥ 0`、
   编制树不变量均由 `Unit`/`UnitState`/`Route` 构造期判，异常同样被 handler 折成拒绝——两处不重复实现。
4. **`at` 取 `state.meta().timestamp()`**（spec §四原文），而非 `UnitSnapshot.timestamp()`；测试中两者一致。
5. 定向命令为留全量证据去了 `-q`（判据等价）。

## 四 我未能核实的

- **端到端未见**：本任务只到 handler 单元边界。handler 经 `CoreSimos` 注册、信封 → 事件链 → revision 的端到端
  由 T1（装配）/T11（判据①②）覆盖；T4 不引用任何端到端结论。
- **真实 replay 状态下 `meta.timestamp()` 与 unit 切片时间戳是否恒同**：未验，仅假定（M4 口径如此）。若分叉，
  带时刻命令的 `at` 口径需复核。
- **PlanRoute 稀疏路径**：见 §三 1，未解决。
- **GUI/MCP 是否真会送"逐格相邻"点列**：未验（T8/T9 落地后才知）。
- **`worldAt` 同刻切片**：测试手工构造，未与真实 `SimulationState` 装配路径对表。

## 五 证据索引

| 文件 | 内容 |
|---|---|
| `t4-evidence/logs/green-targeted.log` | 定向 47 条全绿 |
| `t4-evidence/logs/full-verify.log` | `clean verify` 绿（rc=0、729 条、BugInstance 0 ×5、ERROR 0） |
| `t4-evidence/logs/m1.log` / `m2.log` | 变异轮 + 装置补记（含本轮推送字节的 md5） |
| `t4-evidence/mutants/mut-round.sh` | 九道门禁装置（worktree 根） |
| `t4-evidence/mutants/SetStrengthHandler.m1-ignore-equipment.java` | m1 变异体 |
| `t4-evidence/mutants/CreateUnitHandler.m2-wrong-timestamp.java` | m2 变异体 |
| `t4-evidence/mutants/orig/{SetStrengthHandler,CreateUnitHandler}.java` | 原件快照（md5 与工作树一致） |
