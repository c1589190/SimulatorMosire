# Unit 扩容 —— SDD 台账（裁定与结论）

> 计划：`docs/superpowers/plans/2026-09-20-unit-extension-plan.md`；spec：`docs/superpowers/specs/2026-09-20-unit-extension-design.md`
> worktree：`.claude/worktrees/uet12`，分支 `ue/t12`。本文件只记**裁定与结论**，不记取证过程（证据在 `tN-evidence/`）。

## T1 模型地基（Block 所有下游）

- 落地：`UnitStatus` / `RelativeOffset` / `CommandChainId` / `CommandChain` 四新类型；`Unit` +4 组件 + 兼容构造器；`UnitState` +`commandChains` + `effectivePosition` 五行情形；`UnitChangeSet` 二组件；`UnitCodec` 键反序列化器；四个 canonical 拷贝点连带。
- **结论**：旧行为一字不变（`attached=true`/`offset=empty`/`status=MOVING`/`rejoinTarget=empty`）；`simos-unit` 计数 **131 → 148**（+17）。
- **S1 未触发**：`SegmentedSeries<Boolean>` 与 `SegmentedSeries<Optional<RelativeOffset>>` 均实测可 JSON 往返（`UnitCodecTest` 绿）⇒ 不退化普通字段，时态序列形态保留。
- **裁定（兼容构造器锚时刻）**：取 `parent.segments().get(0).from()`（计划 §三 T1 步骤 3 原文）。语义安全：`SegmentedSeries.baseValueAt` 对 anchor 之前向前恒定延拓，故 `attached` 恒为 true、`offset` 恒为 empty，与旧行为等价。
- **裁定（`requireChainReferencesResolve` 的检查位置）**：放在 `UnitState` 紧凑构造器（与既有 `requireNoCycleAtKeyTimes` 同层）；commander 与每个 member 逐一查 `units`。这是 spec §一.6 不变量 1。
- **变异**：m1/m2/m3/m4 **全 KILLED，0 存活**（日志 `t1-evidence/logs/`），每轮 `restored_md5 == orig_md5`。
- **未触发/未做**：跨 JVM 字节稳定、真档兼容、SpotBugs（归关账轮）——见 `t1-report.md` §五。

## T2 三态速度（E3 / P5 / P6 / P13）

- 落地：`UnitStatus.factorPerMille()`（1000/500/250‰）；`Unit.effectiveSpeed()`；`UnitOperations.planRoute` 冻 `effectiveSpeed`；`UnitOperations.setStatus`；`UnitPayloads.requireStatus/optionalStatus`；`CreateUnitHandler` 可选 `status`（缺省 MOVING）；新增 `SetStatusHandler`。
- **结论**：三态出发速度 8/4/2 可区分且有序；在途改状态不回溯（`Movement` 一字不变）；`CreateUnit` 缺省 MOVING、可显式、未知串拒；`MovementStatus` 与 `UnitStatus` 正交（`UnitMoves.java` 零改动）。
- **裁定 U1（effectiveSpeed 的 clamp）**：`max(1, floorDiv(speed × factorPerMille + 500, 1000))`。理由：`Movement.speedAtDeparture ≥ 1` 硬约束；夹具用 `speed=8` 保三档可区分，`speed≤2` 专钉 clamp。
- **计数**：`simos-unit` **148 → 167**（+19）。
- **变异**：m1/m2/m3 **全 KILLED，0 存活**（`t2-evidence/logs/`），每轮 `restored_md5 == orig_md5`。
- **未做**：`unit.SetStatus` 未注册进 `Shell`（归 T9）；真档 / SpotBugs 归关账轮——见 `t2-report.md` §五。

## 合并后门禁（`feat/adr1-core-scope`，T1+T2 快进合并 `8b8c0c0`）

- `./mvnw clean verify` **rc=0**、**8/8 模块 SUCCESS**、`BugInstance size is 0` **×7**、`[ERROR]` **0 行**、前端 `[frontend-gate] OK tests=88 pass=88 fail=0`。日志 `t2-evidence/logs/merged-full-verify.log` + `verify-rc.txt`。
- **逐模块**：`util 170 / map 362 / social 45 / unit 167 / core 174 / sd 62 / app 116` = **1096**。
- ★ **基线口径更正（诚实披露）**：派单写"基准 1000 = 170/362/45/131/169/110/13"。**该 1000 与本次起始 HEAD `f9f5f0c` 不符**——A3~A6 的 sd 合并（`924bb49 → f9f5f0c`）给 core/sd/app 加了 **+5/+49/+6 = +60** 条用例（git diff 实测），而它**未碰 simos-unit**。⇒ 起始树实测应为 **1060**（`…/unit 131/core 174/sd 62/app 116`）。**本次 T1+T2 的净增量恰为 `simos-unit 131 → 167 = +36`**，其余模块逐值不变（diff 范围仅 `simos-unit` + `.superpowers`）。**1096 = 1060 + 36**。

## T3 编制命令 A（E1 / P1 / P2 / P3）

- **本任务树**：`.claude/worktrees/uet3`，分支 `ue/t3`，基线 HEAD `4138c5d`（T1+T2 合并后）。
- 落地：`UnitOperations.attachSubtree` / `detachUnit` / `setOffset`（+ 私有 `subtreeOf` / `parentAt` / `copyFormation`）；`AttachUnitHandler`（`id, parent`，`parent` 必填）/ `DetachUnitHandler`（`id`）/ `SetFormationOffsetHandler`（`id, dq?, dr?`）；`UnitPayloads.optionalInt`。
- **结论（判据实测）**：attach **级联**（`id` 与全部后代 `attached=true`，且不溢出子树；只有 `id` 换父）／detach **只节点**（子节点与父都不动；`id` 是根 ⇒ 拒）／`attached=true` + 无自身位置 + `offset=(1,0)` ⇒ 有效位置 = 父位⊕(1,0) 且沿父链传播、清偏移 ⇒ 回父位（`T10` 历史值不受影响）／`offset` **不强制落图内**（`(-9999,9999)`）／detached + 无自身位置 ⇒ 空（带偏移也不回退父）／成环 ⇒ **op 内可读理由**、状态不变／部分分量 `{"dr":-2}` ⇒ `(0,-2)`、两者皆缺 ⇒ 清。
- **裁定 1（计划表述缺口，就地裁）**：计划 §三 T3 第 5 步说 `dq`/`dr` 用 `requireInt`，但 `requireInt` 对**缺失**字段是抛，无法表达"任一缺失 = 部分更新"（同句要求"允许其中任一缺失"）——**两句互斥，是计划自身的缺口**。⇒ 在 `UnitPayloads` **新增 `optionalInt`**（镜像既有 `optionalText`/`optionalHex` 形制：缺失/`null` ⇒ 空，非整数 ⇒ 抛），把载荷形状留在载荷类里，handler 不直接摸 `JsonNode`。这是对"**尽量不新增 helper**"的**有意偏离**，理由如上。
- **裁定 2（spec 表未实现的一格，待控制器裁）**：spec §五.2:362 给 `unit.AttachUnit` 列了三条拒绝"不存在；**已是父**；环"——**"已是父"未实现**（计划与派单均未提）。理由：① 重挂同一父正是 **P9 "合体 = 重新 attach"** 的语义，拒掉会把合体堵死；② 级联对子树仍有效（`attached` 段照追加，不是无变化命令）；③ 本操作面**无任何操作**判"无变化命令"（`reparent`/`placeAt`/`setStatus` 同款），单为 attach 加会开先例。同理 **`attached` 已 `true` 的节点不拒**（spec 未要求）。**⇒ 改 spec 表还是补实现，请控制器裁。**
- **裁定 3**：`SpiFixture.java` **零改动**（计划写"若需真三层夹具"）——三层树夹具 `formation(subAttached, leafAttached)` 建在被测类自己的测试里更贴近，且不必动 T1/T2 的既有夹具。三个根一律 `attached=false`：缺省 `true` 会把"级联溢出子树"整个掩盖掉（判别力要求）。
- **计数**：`simos-unit` **167 → 183**（+16 = `UnitOperationsTest` +9 / `UnitCommandHandlersTest` +7）；`util 170 / map 362` 逐值未变。
- **变异**：m1（detach 改级联）/ m2（attach 不级联）/ m3（删 op 内环校验）/ m4（`setOffset` 一律写空）/ m5（handler 层部分分量当清）**全 KILLED，0 存活**（`t3-evidence/logs/`），每轮 `restored_md5 == orig_md5`、`compile_errors=0`、`verdict=OK`。★ **m3 的价值**：删掉 op 的显式拒**不会**让命令通过（`UnitState` 构造期仍抛"编制树…成环"）⇒ 只断言 `IllegalArgumentException` 的话 m3 **会存活**；断言写成 `hasMessageContaining("子树")` 才杀得掉，红点正是那条**消息**断言——护栏保护的是"抛的是命令边界那条可读理由"，不是"会抛"。
- **未做**：三条 handler **未注册进 `Shell`**（归 T9）⇒ 端到端冒烟未做；跨机字节稳定 / 真档兼容未测；`subtreeOf` 复杂度（`O(units × 深度)`）未压测；`parentAt` 对"父链指向不存在 id"的容错无专门用例；**`dq`/`dr` 无上界 ⇒ `appliedTo` 裸 int 加法静默溢出**（当场实测 `1 + Integer.MAX_VALUE = -2147483648`，`HexCoord` 无紧凑构造器校验）——护栏归属待裁；SpotBugs 只在本树 `-am` 的 util/map/unit 三个模块跑过。详见 `t3-evidence/t3-report.md` §五。
