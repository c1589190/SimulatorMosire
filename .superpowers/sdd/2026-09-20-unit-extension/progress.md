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
