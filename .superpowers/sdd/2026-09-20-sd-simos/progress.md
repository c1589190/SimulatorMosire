# SDSimos 台账（progress）

> 配套 spec：`docs/superpowers/specs/2026-09-20-sd-simos-design.md`（§〇~§十四，已无待裁项）
> 实现计划：`docs/superpowers/plans/2026-09-20-sd-simos-plan.md`（bite-sized，A/C/D/E 分期）
> 姊妹计划（阶段 C 前置）：`docs/superpowers/plans/2026-09-20-unit-extension-plan.md`

## 状态

| 项 | 状态 |
|---|---|
| 计划 | ✅ 已落盘（2026-09-20） |
| 阶段 A（骨架 + Nation + 不可删守卫） | 🔄 **进行中**——A1 ✅ / A2 ✅ / A3~A6 ⬜ |
| 阶段 C（Combat，前置 B） | ⬜ 未开工（★ 需姊妹计划 B 完成） |
| 阶段 D（Directive + 判决 + 渠道） | ⬜ 未开工 |
| 阶段 E（关账） | ⬜ 未开工 |

## 裁定与结论

（随执行累积；格式：`# 任务 / 裁定号 / 结论 / 依据`）

### A1 模块骨架与 enforcer

- **worktree**：`.claude/worktrees/sda1a2`，分支 `sd/a1a2`，基线 `4c0469767571051eb9945af7dce9a364ef88fb20`（计划 §〇 单任务 worktree；派发前已核基线）。
- **A1 结论**：`simos-sd` 落地，reactor **8/8**（父 + 7 模块）；`./mvnw -pl simos-app -am -DskipTests compile` rc=0；sd 的 `excludes` = `{simos-core, simos-app}` 且**不含** `agentlib-mosire`；core main 的 `excludes` 加 `simos-sd`（R2）。
- **裁定 A1-a（执行期取代说明，计划 m2 逻辑缺陷）**：计划 §三 A1 的 m2 写「把 `io.mosire:simos-core` 从 `simos-sd` 的 `excludes` 删掉、并给 sd 加 core 依赖 ⇒ 期望 sd enforcer 报错」。**这不可能报错**——删掉 exclude 正是**解除**禁令（实测 plan-as-written 轮 `rc=0`，日志 `a1-evidence/mutants/m2-plan-as-written/plan-as-written-run.log`）。⇒ 取代为**保留 exclude、只给 sd 加 core 依赖**（这才检验"sd 不得依赖 core"），实测 `BannedDependencies failed`、红点 = `io.mosire:simos-core ... <--- banned`。
- **裁定 A1-b（装置正则假阴性）**：首轮 m1 曾因检测正则写成小写 `bannedDependencies`（日志是 `BannedDependencies`，大写）而**假判"enforcer 未触发"**——命中 0 先怀疑自己的正则（CLAUDE.md 纪律）。已改为 `grep -ci "BannedDependencies failed"`。
- **A1 判据实测**：m1（core 加 sd 依赖）⇒ `BannedDependencies failed`、红点 `io.mosire:simos-sd ... <--- banned via the exclude/include list`；m2 ⇒ `io.mosire:simos-core ... <--- banned`；两轮 `COMPILATION ERROR=0`、md5 逐字节还原相等、接收侧干净树 `BannedDependencies passed=2`。

### A2 数据模型（ID 三件套 + spec §三 全部 record）

- **A2 结论**：落地 **11 个 ID**（含 `CombatStateId`）+ **30 个 model 类型**（spec §三 全部）；定向 `SdIdTest`(2) + `SdModelTest`(11) = **13 条绿**；`./mvnw -pl simos-sd -am verify` rc=0、sd `BugInstance size is 0`、`COMPILATION ERROR=0`。
- **裁定 A2-a（spec §二.1 ID 表漏列 `CombatStateId`）**：§二.1 的表只列 `CombatStageId`/`CombatOutcomeId`，但 §三.1 的 `SdState` 与 §三.3 的 `CombatState` 都用到 `CombatStateId`（计划 A3 的 `SdCodec` 键清单也含它）⇒ **以 §三 为准**，补齐为**第 11 个 ID**。
- **裁定 A2-b（`Condition`/`Trigger` 与 `AtTick`/`AtOrAfterTick` 二名并存）**：spec §三.3 写 `Condition`/`AtOrAfterTick`，计划 §三 A2 写 `Trigger`/`AtTick`。取 **`Trigger`**（与 §三.6 一致）与 **`AtOrAfterTick`**（与 §十一.5 判据原文一致）。
- **裁定 A2-c（spec 未定义字段形状的三个类型，实现期定形）**：`CasualtySpec`（定为非负幅度 + 冻结 map——预期损失规模；真负增量由 `CasualtyDelta` 承载）、`AdjudicationBreakpoint`（定为稳定标识 record，D2 的 `Breakpoints` 以常量引用）、`DisclosurePolicy`/`DirectiveStatus`（取值 spec 未定义，实现期定档）。**凡此均为 spec §十四 假设 5 允许的"设计形状微调"**，未改 spec 正文。
- **裁定 A2-d（sealed 多态预置 Jackson 类型信息）**：`Affiliation`/`Trigger`/`Action` 是 sealed 多态（同 `FieldDelta` 同族），裸往返不可能 ⇒ 现在就把 `@JsonTypeInfo(Id.NAME)` + `@JsonSubTypes` **注解钉在类型上**（A3 做往返时直接可用）。这是计划 A2 未点名、但为下游必需的前瞻性补齐。
- **裁定 A2-e（计划预期 sd +12，实测 +13）**：多出 1 条来自 `SdIdTest` 的"空白/null 全类型拒绝"用例。以实测为准。
- **A2 变异轮（3 个全杀）**：m1 删 `OutcomeOption.weight > 0` 校验 ⇒ `outcomeOptionRejectsNonPositiveWeight` 红；m2 删 `OutcomeTable` 空表校验 ⇒ `outcomeTableRejectsEmptyOptions` 红；m3 删 `CasualtyDelta` 装备负值校验 ⇒ `casualtyDeltaRejectsNonNegativeEquipment` 红。三轮 `COMPILATION ERROR=0`、`Tests run≥1`、surefire 报告 mtime 落本轮、`cp` 逐字节还原相等、日志自指（聚合 md5 `97e27b04e46fd33169692de4de0ccaf5`）。

## 我未能核实的

- 计划期**未跑任何 Maven**：§附的门禁数字为**推演**，非实测（A1/A2 已实测，见下）。
- A1/A2 本机实测：worktree `./mvnw -pl simos-sd -am verify` rc=0、sd `BugInstance size is 0`、13 条 sd 用例；**主树全量 `clean verify` 的逐模块数字以关账轮为准**（见 `a1a2-report.md`）。
- `simos-sd` 的 reactor 计数已实测 **8/8**（父 + 7 模块）。
- 姊妹计划 `2026-09-20-unit-extension-plan.md` 是否落盘/关账**未核实**（不属 A1/A2 范围）⇒ 阶段 C 前置状态未核。
- `SdState`/`SdChangeSet`/`SdCodec`（A3）**尚未落地**；`Affiliation`/`Trigger`/`Action` 的 Jackson 往返**只在 A3 才会真跑**（注解已预置，但"能往返"未经 A2 实测）。
