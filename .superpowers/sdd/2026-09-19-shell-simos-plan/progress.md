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
| T1 | `simos-app` 骨架与装配门面 | ✅ `m5/t1` → 合并 `33fa15f`（冒烟 4 条；**2 轮变异 0 存活**；reactor 七模块首绿） |
| T3 | 查询层 + 两个真 Facet | ✅ `m5/t3` → 合并 `009e8b3`（27 条新测试；**2 轮变异 0 存活**） |
| T8 | GUI 服务器与 `/api` | ✅ `m5/t8` → 合并 `5ac368b`（14 条新测试；**2 轮变异 0 存活**）★ WebUI 1/2 |
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

---

### Batch B 已关账（T1，2026-09-19 04:4x，子代理 + 控制器核验）

**T1**（`m5/t1` `aa21aea` → 合并 `33fa15f`）：新建 `simos-app`（父 POM 加模块，reactor 六 → **七**）；`ShellConfig`/`Shell`/`ShellMain` + `log4j2.xml`；装配 **3 codec + 8 handler + 1 participant**；冒烟 4 条（改名全链 / 推进全链 / `branches·head` / close 幂等）；**2 轮变异 0 存活**（m1 不注册 `UnitCodec`；m2 不注册 participant）。合并后主树门禁 **735** 条 = 170/255/37/**122**/**147**/**4**、`BugInstance size is 0` **×6**、`[ERROR]` 0、7/7 模块。

**裁定 57 —— T1 的三处执行期校正/新增，全部接受**：
1. **`ShellConfig` 补 `String mapId`**（spec §3.1 漏写；`UnitTimeParticipant(MovementCost, String mapId)` 构造需要）⇒ **取代说明**：该节应读作"record 头 + `mapId`"。
2. `TerrainMovementCost.INSTANCE` 取代派单文字的 `new TerrainMovementCost()`（其构造器私有）——就地校正，语义不变。
3. 新增 `simos-app/src/main/resources/log4j2.xml`（否则 `ShellMain` 的 INFO 配置行不可见）+ **provided 的 `spotbugs-annotations`**（供 `Shell.coreSimos()` 的 `EI_EXPOSE_REP` **精确豁免**）——★ **门禁首次七模块 `verify` 真抓到该缺陷**（`BugInstance size is 1`，其余六模块 SUCCESS）；修后 0。★ 如实记：失败那次的日志被随后的绿跑**同名覆盖**、无独立留档（形态 1 一族）。
★ **m1 的实测修正（不照抄派单预测）**：派单预测 rename 会走 `Rejected`；实测是 **`IllegalStateException`**——`UnitCodec` 缺席时在 `stateLoader.load(base)`（重放创世 checkpoint）阶段就抛，**根本没走到 handler**（`Replay.decodeCheckpoint` 对未装配 namespace 的既定契约）。红是真的且由被保护行造成，但**红的形态是异常不是拒绝**；如实记，不改 Core。

**下一批**：Batch C = **T3**（查询层 + 两个真 Facet）→ 随后 **T8/T9（WebUI 出形）**。

---

### Batch C 已关账（T3，2026-09-19 05:0x，子代理 + 控制器核验）

**T3**（`m5/t3` `6d81dac` → 合并 `009e8b3`）：`UnitsHereFacet`（unit）+ `PopulationFacet`（social）+ `QueryService`（app）+ Shell 装配两注册表并暴露 `queryService()`；27 条新测试（9/8/10）；**2 轮变异 0 存活**（m1 R6 转发改时间戳 ⇒ 红在转发断言；m2 R7 少注册一面 ⇒ 红在 `facetNames` 完整性）。合并后主树门禁 **762** 条 = 170/255/**45**/**131**/147/**14**、`BugInstance size is 0` ×6、`[ERROR]` 0。

**裁定 58 —— T3 的口径细化（全部接受并回填 spec/计划）**：
1. **`unitsHere` 值形态细化**：**一单位一条** `FacetEntry("unit", <name>, "Unit", "unit:<id>")`（按 id 字典序），取代 spec §5.2 的 `List<String>` 表述——结构化条目对 GUI/MCP 更有用。**spec §5.2 已回填**。
2. **facet 只认 canonical `hex.<q>_<r>`（Entity 段）**：Index/Human 形（`map:Map1:[1,1]`）⇒ 空列表；且 `QueryService.facets` 按 R6 **不改写**转交 ⇒ **调用方（T5/T8）要么先 `resolve`、要么直接传 canonical**。★ **这是给 T8 的硬接缝**。
3. **非法坐标名（`hex.xyz`）⇒ 空列表**（facet 契约"空=没有内容，不是错误"），与 `MapResolver`"认领即抛"**有意不同**；两个 facet 各钉一条。
4. `PopulationFacet.label` 取 `q_r`（spec 未指定，补白）；`QueryTarget` 内嵌于 `QueryService`（派单只列一个文件）。
★ **R7 的落地修正**：util 的 `FacetRegistry` **只有** `register/facetNames/queryAll`、**无按名查询** ⇒ spec §5.1"未注册 facet 名 ⇒ 明确失败"在**本层无 API 可达**；R7 落为"注册集合完整性 + queryAll 结果"（未注册 namespace 的明确失败有直证）。

**下一批**：Batch D = **T8 → T9（WebUI 出形）**。

---

### Batch D-1（T8 GUI 服务器）已关账（2026-09-19 05:3x，子代理 + 控制器核验）

**T8**（`m5/t8` `abb2039` → 合并 `5ac368b`）：`gui/{GuiServer(592),ApiViews(238),StaticHandler(93)}` + `webui/index.html`（最小骨架）；Shell 起/停 GUI（close 次序 GUI 第一）+ `boundGuiPort()`；全端点（读 8 + 写 3 + 审批代理接缝 503）；**2 轮变异 0 存活**（m1 R1 扫描加真写路径直引 ⇒ 红在扫描断言；m2 R4 initiator 改 `player:local` ⇒ 红在身份断言）；14 条新测试。合并后主树门禁 **776** 条 = 170/255/45/131/147/**28**、`BugInstance size is 0` ×6、`[ERROR]` 0。

**裁定 59 —— T8 的口径（全部接受）**：
1. **R1 扫描"扫代码、不扫注释"**：spec §十一 R1 的字面是"app 源码不出现 `SqliteStore`/`Timeline`/`CheckpointStore`"，而 `Shell` 的 javadoc **有意**写着这句禁令本身。⇒ 护栏先 `stripComments` 再判串；stripper 由专项用例自证（注释去掉、代码与字符串保留），并**由 m1 反向证明它真能抓住代码引用**（若 stripper 抹掉一切，m1 不红）。**不回填 spec**，记此取代说明。
2. `ApiViews.java` 是**计划外第三文件**（视图与路由分离）——接受。
3. 审批接缝：**未配置 ⇒ 503**（照派单）；**已配置 ⇒ 501 Not Implemented**（T6 未落地、无法实测转发）——接受，**T6 落地时必须改这里**。
4. `ShellMain` 启动日志改打**实际** GUI 端口（0 时回显配置值会骗人）。
5. GUI **恒绑 127.0.0.1**（回环基线，不提供改绑口子）。

**下一批**：Batch D-2 = **T9（前端三页）**。
