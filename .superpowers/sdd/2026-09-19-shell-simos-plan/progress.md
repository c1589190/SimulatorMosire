# SDD ledger — plan: docs/superpowers/plans/2026-09-19-shell-simos-plan.md

> **本文件是 M5（Simos 外壳）的台账与恢复地图。** 上游 spec `docs/superpowers/specs/2026-09-19-shell-simos-design.md`（**已获用户批准 2026-09-19**）；AgentLib 扩展需求书 `2026-09-19-agentlib-extension-requirements.md`。
> 执行 = 控制器 + 子代理。**排序 = WebUI-first**（用户 2026-09-19 原话「尽快把 WebUI 整出来」）。
> **跨任务约束**：本机 `nproc=2` 并行上限 2；worktree 隔离（`.claude/worktrees/m5tN`）；变异九道门禁（CLAUDE.md 形态清单）；禁 `mvn install`；显式 `git add`；不 `-A`；该推就推。
> **台账记裁定与结论，不记取证过程。**

**基线**：`feat/adr1-core-scope`（M4 关账 `cf4a05f` + M5 spec `22888d0` + 计划提交）。

---

## 任务地图（12 任务；执行序 WebUI-first）

| # | 任务 | 状态 |
|---|---|---|
| T4 | unit 命令面补齐（7 条 handler） | ✅ `m5/t4` → 合并 `6926ae1`（29 条测试；**2 轮变异 0 存活**） |
| T2 | core 只读扩展（`branches/head`） | ✅ `m5/t2` → 合并 `d475489`（+2 条；**1 轮变异 0 存活**） |
| T1 | `simos-app` 骨架与装配门面 | ⏳ Batch B |
| T3 | 查询层 + 两个真 Facet | ⏳ Batch C |
| T8 | GUI 服务器与 `/api` | ⏳ Batch D ★ WebUI 1/2 |
| T9 | GUI 前端三页 | ⏳ Batch D ★ WebUI 2/2 |
| T5 | 工具集（3 写 + 9 读） | ⏳ Batch E |
| T6 | 审批装配 | ⏳ Batch E |
| T7 | MCP 服务装配（判据②前置） | ⏳ Batch E |
| T10 | AgentBinding | ⏳ Batch F |
| T11 | 判据端到端 ①② | ⏳ Batch F |
| T12 | M5 关账 | ⏳ Batch F |

**批次**：A(T4‖T2) → B(T1) → C(T3) → D(T8→T9) → E(T5‖T6→T7) → F(T10→T11→T12)。

---

## 执行日志（裁定与结论随任务关账追加）

### Batch A 已关账（T4 ‖ T2，2026-09-19 04:3x，子代理并行 + 控制器核验）

**T4**（`m5/t4` `4b774b1` → 合并 `6926ae1`）：7 个 handler + `UnitPayloads`（形状/类型校验，坏载荷 ⇒ `IllegalArgumentException` ⇒ `Rejected`）+ 29 条测试；**2 轮变异 0 存活**（m1 `SetStrength` 忽略 equipment ⇒ 红在"整份替换"逐值；m2 `CreateUnit` 时间戳写 0 ⇒ 红在"初始段 = base 时间戳"）。任务内全量 729（unit 93→122）。
★ **取代说明候选（真缺口，非笔误）**：`unit.PlanRoute` 载荷只给 `waypoints`，**无法表达**"稀疏 waypoints + 更细 path"（M3 `Route(waypoints, path)` 允许子序列）；handler 采唯一可重建口径 `Route(waypoints, waypoints)` ⇒ 要求点列**逐格相邻**。GUI/MCP 若要稀疏路径，须先补载荷字段（`path` 或自动 A*）。**不在 Batch A 裁决**，记待办（T9 路线表单前复核）。

**T2**（`m5/t2` `d5b1ac0` → 合并 `d475489`）：`CoreSimos.branches()/head(BranchId)` 只读委托 + 2 条测试；**1 轮变异 0 存活**（`Timeline.head` 忽略 branch ⇒ 红在 b2 head 断言）。任务内全量 702（core 145→147）。
★ 两任务都如实记了"未能核实"（T4：端到端归 T1/T11、`meta.timestamp()` 与切片时间戳恒同性未验；T2：并发/关闭后调用未验）。

**合并后主树门禁**：`clean verify` rc=0、**731** 条 = 170/255/37/**122**/**147**、`BugInstance size is 0` ×5、`[ERROR]` 0 行；日志 `task-.../t4-evidence/logs/full-verify.log`。
★ **T1 的一处 spec 缺口（派单时已告知执行者）**：spec §3.1 的 `ShellConfig` 漏了 `mapId`，而 `UnitTimeParticipant(MovementCost, String mapId)` 构造需要它 ⇒ T1 补 `mapId`（缺省 `Map1`）并记取代说明候选。

**下一批**：Batch B = **T1**（`simos-app` 骨架 + 装配门面）。
