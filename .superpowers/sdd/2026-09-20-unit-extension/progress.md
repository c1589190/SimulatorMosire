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
