# T3 执行报告 —— 编制命令 A（attach 级联 / detach 只节点 / SetFormationOffset）

计划：`docs/superpowers/plans/2026-09-20-unit-extension-plan.md` §三 T3；spec：`docs/superpowers/specs/2026-09-20-unit-extension-design.md` §一.3 / §一.4 / §五.2 / P1 / P2 / P3。
日期：2026-09-21。worktree：`.claude/worktrees/uet3`，分支 `ue/t3`，基线 HEAD `4138c5d`。依赖 T1（`UnitStatus`/`RelativeOffset`/`offset` 字段 + `effectivePosition` 五行情形）。

## 一、落地内容

新增 main（`simos-unit/src/main/java/io/mosire/simos/unit/spi/`）：
- `AttachUnitHandler.java`（`unit.AttachUnit`，载荷 `id, parent`；`parent` **必填**，缺失/`null` ⇒ 拒）
- `DetachUnitHandler.java`（`unit.DetachUnit`，载荷 `id`）
- `SetFormationOffsetHandler.java`（`unit.SetFormationOffset`，载荷 `id, dq?, dr?`；两者皆缺 ⇒ 清偏移；只给一个 ⇒ 另一个按 `0` 补）

修改 main：
- `ops/UnitOperations.java`：`attachSubtree` / `detachUnit` / `setOffset` 三个纯函数操作 + 私有 `subtreeOf` / `parentAt`（父图取 `at` 时刻的值，与 `UnitState.requireNoCycleAtKeyTimes` 同口径）+ 私有 `copyFormation`（**T3 的 canonical 拷贝点**：显式给 `parent`/`attached`/`offset`，`status`/`rejoinTarget` 原样带过；三个形参类型两两不同 ⇒ 传错顺序是编译错误）
- `spi/UnitPayloads.java`：新增 `optionalInt`（见 §三 分歧 1）；类注释 七个 → 十一个 handler

修改 test：
- `ops/UnitOperationsTest.java`：**+9 条**（16 → 25），三层树夹具 `formation(subAttached, leafAttached)`（`u-root → u-sub → u-leaf` + 独立根 `u-other`，三个根一律 `attached=false`，否则"级联溢出子树"不可判别）
- `spi/UnitCommandHandlersTest.java`：**+7 条**（34 → 41）+ 新夹具 `detachedPair()` / `attachedLine()`

**未改动**：`SpiFixture.java`（真三层夹具直接建在 `UnitOperationsTest` 内，够用且不动 T1/T2 的既有夹具）；`simos-app`（handler 注册归 **T9**，见计划 §T9 "SPI 装配"——`Shell.java:193-213` 一带）；`UnitState.java`（成环的构造期兜底一字未动）。

## 二、判据实测值

| 判据（spec §八 / plan §三 T3） | 实测值 |
|---|---|
| attach **级联**：`id` 与**全部后代** `attached=true` | 绿：`u-sub` 与 `u-leaf` 在 `T10` 均 `true`（`attachCascadesAttachedToTheWholeSubtree` / `attachUnitCascadesToTheWholeSubtree`） |
| 级联**不溢出** `id` 的子树 | 绿：原父 `u-root` 与新父 `u-other` 仍 `false`；`u-other` 的 `parent` 仍空 |
| 只有 `id` 换父、后代的 `parent` 不动 | 绿：`u-leaf.parent(T10) == u-sub` |
| **追加段**（不是替换）：`T0` 仍为旧值 | 绿：`u-sub.attached(T0)` 仍 `false`；`base` 的段数仍是 1（纯函数） |
| detach **只节点**（P3 的不对称） | 绿：`u-sub` → `false` 而 `u-leaf` 仍 `true`、`parent` 不动（`detachTouchesOnlyTheNodeItself` / `detachUnitTouchesOnlyTheNode`） |
| detach 已是根 ⇒ 拒 | 绿：`u-root` / `u-other`（两个根同判）⇒ `已是根` |
| attach 成环 ⇒ **op 内可读理由** | 绿：`parent` = 后代（`u-leaf`）与 `parent` = 自身（`u-sub`）两条路径均 `hasMessageContaining("子树")`，且状态不变（`base` 段数仍 1） |
| attach 目标/父不存在 ⇒ 拒 | 绿：`单位不存在` / `父单位不存在` |
| `attached=true` + 无自身位置 + `offset=(1,0)` ⇒ 有效位置 = 父位 ⊕ 偏移 | 绿：`H11` → `HexCoord(2,1)`；沿父链传播到 `u-leaf`（它也 `(2,1)`） |
| 清偏移 ⇒ 回父位；`T10` 的历史值不受影响 | 绿：`T20` 回 `H11`，`T10` 仍 `(2,1)`；`offset` 段数 2（追加不是替换） |
| `offset` **不强制落图内**（P2） | 绿：`RelativeOffset(-9999, 9999)` ⇒ `HexCoord(1-9999, 1+9999)` |
| detached + 无自身位置 ⇒ 空（即便带偏移也不回退父） | 绿：`empty`；`u-leaf` 亦空（父无位可给） |
| `dq`/`dr` 部分给 | 绿：`{"dr":-2}` ⇒ `RelativeOffset(0,-2)`；`{"dq":1,"dr":0}` ⇒ `(1,0)`；两者皆缺 ⇒ `empty` |
| 坏形状载荷 ⇒ 拒 | 绿：`"1"` / `1.5` ⇒ `整数`；`u-404` ⇒ `单位不存在`；`{}`（attach/detach）⇒ `id` |
| **simos-unit 计数** | **167 → 183（+16）**：`UnitOperationsTest` +9（16→25）、`UnitCommandHandlersTest` +7（34→41）。原始行见 §七。 |

`util 170 / map 362` 逐值未变（T2 合并态）。前端 88/88 未跑（T3 零前端改动）。

## 三、与 spec / 计划的分歧（裁定，逐条）

1. **`optionalInt` 是新增 helper（计划 §三 T3 第 5 步原文说 `dq`/`dr` 用 `requireInt`）**。`requireInt` 对**缺失**字段是抛，无法表达"任一缺失 = 部分更新"。⇒ 在 `UnitPayloads` 新增 `optionalInt`（**镜像既有 `optionalText`/`optionalHex` 的形制**：缺失或 `null` ⇒ 空 Optional，非整数 ⇒ 抛），把"载荷长什么样"留在载荷类里，避免 handler 内直接摸 `JsonNode`。这是**计划自身的表述缺口**（"用 requireInt" 与 "允许其中任一缺失" 互斥），不是实现偷懒。
2. **spec §五.2:362 的 `unit.AttachUnit` 拒绝清单里的"已是父"未实现**（计划与派单均未提这一条）。裁定**不实现**，理由三条：① 重挂同一父正是 **P9 "合体 = 重新 attach"** 的语义，拒掉会把合体堵死；② 级联对子树仍然有效（不是无变化命令：`attached` 段照追加）；③ 本操作面**没有任何操作**判"无变化命令"（`reparent` / `placeAt` / `setStatus` 同款口径）——单独为 attach 加一条会开先例。**spec 表与实现的这一格不一致，记在台账 T3 段，供控制器裁定是改表还是补实现。**
3. **`attached` 已是 `true` 的节点不拒**（同上第 ③ 条理由）。spec 未要求，此处只是把"有意不拒"写明在 op 的 Javadoc 里，免后人当疏漏。

## 四、变异轮（九道门禁，装置 `mutants/mut-round.sh`）

装置对 T2 的版本加固了四处：**③ 白名单**（`TARGET` 只准是两条规范路径之一，防止"按变异体文件名拷入"把红变成编译错误）、**④ 扫源树内 `m?_*.java` 残留**、**⑥ surefire 报告 mtime 必须 ≥ 本轮 `round_start`**（陈旧产物当场判 `VOID`）、**⑤⑥ 判定写进日志**（`verdict=OK/VOID(...)`，不只打终端）。变异体由 `mutants/make-mutants.py` 从**当前原件**精确替换生成，任一处锚点没命中即非零退出。

| # | 变异体 | 靶子 | 实测 | 红点（⑦ 逐条核过，落**被保护的那行**） |
|---|---|---|---|---|
| m1 | `detachUnit` 改成**级联**（整棵子树 `false`） | P3 的不对称 | **KILLED** rc=1 / Failures 2 / compile_errors 0 | `UnitOperationsTest.detachTouchesOnlyTheNodeItself:384 [u-leaf（子节点）不动]`、`UnitCommandHandlersTest.detachUnitTouchesOnlyTheNode:540 [u-3（子节点）不动]` |
| m2 | `attachSubtree` **不级联**（只标根：`for (UnitId member : List.of(id))`） | P3 的级联 | **KILLED** rc=1 / Failures 2 / compile_errors 0 | `UnitOperationsTest.attachCascadesAttachedToTheWholeSubtree:340 [u-leaf（后代也 true）]`、`UnitCommandHandlersTest.attachUnitCascadesToTheWholeSubtree:509 [级联到后代]` |
| m3 | **删掉 op 内的成环显式拒** | "给可读理由" | **KILLED** rc=1 / Failures 2 / compile_errors 0 | `UnitOperationsTest.attachRejectsAParentInsideTheSubtree:356 [u-leaf 是 u-sub 的后代]`（断言原文：`Expecting throwable message: "编制树在 SimosTimestamp[tick=10, …] 成环，环上含 u-sub" to contain: "子树" but did not`）、`UnitCommandHandlersTest.attachUnitRejectsACycleUnknownUnitsAndAMissingParent:521 [u-3 是 u-2 的后代]` |
| m4 | `setOffset` **无视入参、一律写空** | P2 的偏移语义 | **KILLED** rc=1 / Failures 4 / compile_errors 0 | `UnitOperationsTest.anOffsetIsNotRequiredToStayInsideTheMap:432`、`anOffsetShiftsTheEffectivePositionOfAnAttachedChild:413`、`UnitCommandHandlersTest.setFormationOffsetAppliesAndClears:559`、`setFormationOffsetAcceptsAPartialComponent:574` |
| m5 | handler 层"**只给一个分量 ⇒ 当清偏移**" | 部分分量语义 | **KILLED** rc=1 / Failures 1 / compile_errors 0 | `UnitCommandHandlersTest.setFormationOffsetAcceptsAPartialComponent:574` |

**存活：0。** 五轮 `restored_md5 == orig_md5`、`compile_errors=0`、`verdict=OK`（无一轮被判 VOID）。日志：`logs/m1.log`~`logs/m5.log`（每份日志尾部有自指的 `orig_md5`/`mutant_md5`/`pushed_md5`/`restored_md5` 块 + 失败清单原文）。

★ **m3 值得单说**：删掉 op 的显式拒**不会**让命令"通过"——`UnitState` 构造期仍会抛"编制树…成环"。若用例只断言 `IllegalArgumentException`，m3 就会**存活**（这是个真等价变异体）。我把断言写成 `hasMessageContaining("子树")`，m3 才被杀，且红点正是那条**消息**断言。⇒ 用例保护的**不是"会抛"，而是"抛的是命令边界那条可读理由"**。

★ **m4 的判别力**：4 条用例同时红（op 层 2 条 + handler 层 2 条），说明"偏移真的参与了 `effectivePosition`"在两层都有独立观察点。

★ **干净轮先跑**：`logs/clean.log`（同一 selector，85 条全绿、`rc=0`、四份报告 mtime 落在干净轮内），变异轮的数字才被采信。

## 五、我未能核实的

1. **`unit.AttachUnit` / `DetachUnit` / `SetFormationOffset` 未注册进 `Shell`**（归 **T9**），故**端到端冒烟没做**——三条命令尚未经 `CommandBus` / `CoreSimos` / GUI / MCP 走过一次。"handler 能被注册表接受"这件事（`type()` 满足 `<namespace>.<Command>` 构造期校验）**只有同构证据**（与既有两个 handler 同形 + `typeNamesMatchTheSpecTable` 逐值），**没有真注册过一次的实测**。
2. **跨机字节稳定 / 真档兼容未测**：附着在真档上的 `attached`/`offset` 段仍是旧档默认值（`true`/`empty`），故"旧档一字不变"是**推断**（T1 已证字段级往返），本任务没在真档上跑过。
3. **`offset` 与"大编制移动时终点随动"（E2/P7/P8）的关系未验**：那属于 T4 的回归路径，本任务只证了 `effectivePosition` 的静态算式。
4. **`subtreeOf` 的复杂度未量**：`O(units × 深度)`（每个单位各自走父链），在深树/大编制上未压测；语义正确性只用三层树证过。
5. **`subtreeOf` 对"手工拼装的状态"的容错边界未系统证**：`parentAt` 对"父链指向不存在的 id"返回空（防死循环），这条**没有专门用例**（`UnitState` 构造期通常已挡掉，只有绕过构造期校验的夹具才够得着）。
6. **`dq`/`dr` 的数值范围未设上界**：`optionalInt` 允许任意 `int`，而 `RelativeOffset.appliedTo` 是**裸 int 加法**（`new HexCoord(hex.q() + dq, hex.r() + dr)`）、`HexCoord` 是无紧凑构造器校验的裸 record（源码核过）⇒ 会**静默溢出**。**当场实测**（`java /tmp/Ovf.java`）：`q=1 dq=2147483647` ⇒ `q+dq=-2147483648`、`overflowed=true`。spec 只说"不强制落图内"，没说要不要防溢出 ⇒ 未加护栏，**如实记下**（护栏归属待裁：payload 层收窄范围，还是 `appliedTo` 用 `Math.addExact` 抛）。
7. **SpotBugs 只在本树 `-am` 的三个模块上跑过**（util/map/unit），`social/core/sd/app` 未跑 ⇒ "本文件干净"只在**这个类集**下成立（纪律形态 6）。

## 六、执行期取代说明

- **无 spec 取代**。计划 §三 T3 的五步、文件清单、判据逐条落地；`attachSubtree` 的判据（"三层树 `r→a→b`，attach `a` 到新父 ⇒ `a` 与 `b` 的 `attached` 均 true"）**逐字**落在 `attachCascadesAttachedToTheWholeSubtree` 上。
- **计划自身的一处表述缺口**（`requireInt` 无法表达"允许缺失"）已在 §三 分歧 1 记录并就地裁定，未静默改口径。
- 计划说"修改 test：… `SpiFixture.java`（**若需**真三层夹具）"——实测**不需要**（三层夹具建在被测类自己的测试里更贴近，且不动 T1/T2 的既有夹具），故 `SpiFixture.java` 零改动。

## 七、本树模块级门禁（`./mvnw -pl simos-unit -am verify`，含 SpotBugs）

- **rc=0**；`[ERROR]` **0 行**；`BugInstance size is 0` **×3**（`simos-util` / `simos-map` / `simos-unit`）；`You have 0 Checkstyle violations.` ×4；Spotless 绿。
- **逐模块**：`UtilSimos 170 / MapSimos 362 / **UnitSimos 183**`。原始行：
  `[INFO] Tests run: 183, Failures: 0, Errors: 0, Skipped: 0`（`logs/verify.log`，rc 存 `logs/verify-rc.txt`）。
- **167 → 183 = +16**，与 §二 的逐类增量（9 + 7）逐值对上。
- ★ **全场只跑了模块级 `verify`**：派单明令**不跑全仓 `clean verify`**（那是控制器合并进主树后的活）⇒ 主树合并门禁值不在本任务范围内。
- ★ **变异证据与当前字节的关系已实测**：五轮跑完后我又跑了一次 `spotless:apply`（为规范化新增的测试 Javadoc），随后 `md5sum -c logs/origin-md5.txt` 五个生产文件**全部 OK** ⇒ 断言"变异轮的对象就是现在树上的字节"，无需重跑（不是推导，是当场核过）。
