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
| T4 | unit 命令面补齐（7 条 handler） | 🔄 Batch A（worktree `m5t4`） |
| T2 | core 只读扩展（`branches/head`） | 🔄 Batch A（worktree `m5t2`） |
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

（待第一批关账后填写）
