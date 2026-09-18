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
| T9 | GUI 前端三页 | ✅ `m5/t9` → 合并 `6917365`（5 条新测试；**1 轮变异 0 存活**；★ 截图 7 张在案）★ WebUI **出形** |
| T9b | 可运行性收尾（计划外） | ✅ `m5/t9b` → 合并 `e2df025`（+8 测试；**2 轮变异 0 存活**；★ 单位标记截图在案） |
| T5 | 工具集（3 写 + 9 读） | ✅ `m5/t5` `a62bb6a` → 合并（11 条测试；**2 轮变异 0 存活**；★ 门禁抓 5 个真项） |
| T6 | 审批装配 | ✅ `m5/t6` `337c9f7` → 合并 `3957a1a`（3 条测试；**1 轮变异 0 存活**；5711 代理落地） |
| T7 | MCP 服务装配（判据②前置） | ✅ `m5/t7` `7f9322b` → 合并（4 条测试；**2 轮变异 0 存活**；★ R2 按"M5 无内部工具"存档） |
| T10 | AgentBinding | ✅ `m5/t10` `03e5a5f` → 合并 `b2dedd2`（12 条测试；**1 轮变异 0 存活**） |
| T11 | 判据端到端 ①② | ✅ `m5/t11` `e130d67` → 合并 `559c6f2`（3 用例类；**3 轮变异 0 存活**；★ **判据①②端到端闭合**；825） |
| T12 | M5 关账 | ✅ 本次（判据逐条实测值 + R1~R9 点验 + 门禁 825 + `CLAUDE.md` + `task-12-final-report.md`） |

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

---

### Batch D-2（T9 前端三页）已关账（2026-09-19 05:3x，子代理 + 控制器视觉核验）

**T9**（`m5/t9` `2ab94e9` → 合并 `6917365`）：`webui/{map,unit,social}.html` + 每页 `map.js(312)/unit.js(411)/social.js(100)` + 共享 `api.js(132)/app.js(184)/styles.css(287)` + `index.html` 接线；`WebuiAssetsTest`（5 条：资产在册非空 / classpath 打包 / **无绝对 URL 无 CDN** / 三页相对引用 / **判定器自证**）；**1 轮变异 0 存活**（往 `map.js` 塞 `https://cdn…` ⇒ 红在 `:92`）。合并后主树门禁 **781** 条 = 170/255/45/131/147/**33**、`BugInstance size is 0` ×6、`[ERROR]` 0。
★ **控制器视觉核验（读了截图）**：`/map` 正确画出走廊 3 格的 pointy-top 六角（`main · rev 1 · tick 7`、图例 `desert=3`）；`/unit` 有单位表 + 8 命令动态表单（branch/expectedRevision/提交）。**中文显示为方框**＝headless 环境**无 CJK 字体**（`fc-list` 空），非页面缺陷。
★ **T9 自证的两次真缺陷**：① 页面 JS 在 `<div>` 上用 `.elements[...]`（`pageerror: reading 'id'`）——截图 QA 当场抓到并修；② 绝对 URL 扫描器把引号当标识符，**`//cdn…` 协议相对形式漏检**——其自带自证用例抓到并修。**两者若没跑真 QA/自证都会潜伏**。

**裁定 60 —— T9 的口径与一处 UX 缺口**：
1. 每页一个 JS + 三个共享资产（spec 只点名共享三件）——接受（便于 `node --check` 与无绝对 URL 扫描统一）。
2. 区域描边延后（种子地图 `regions` 为空）——接受。
3. ★ **单位标记未画在全局 Canvas**（派单本说数据来自 `/api/map/overview` **与** `/api/units`；实现只做了点击格子的 `unitsHere` facet）——**真 UX 缺口**，待人补：用 `/api/units` 的 `effectivePosition` 叠加标记。归 **T9b**。
4. 审批面板 503 优雅降级（T6 接入后改）——接受。
5. ★ **可运行性缺口**：`ShellMain` 不种世界 ⇒ 空库启动后页面无数据（`/api/map/overview` 失败）。归 **T9b**（加 `--demo` 首启种子 + 地图单位标记）。

**下一批**：**T9b（demo 首启 + 地图单位标记）** → 然后 Batch E（T5 ‖ T6 → T7）。

---

### Batch D-3（T9b 可运行性收尾）已关账（2026-09-19 05:5x，子代理 + 控制器视觉核验）

**T9b**（`m5/t9b` `d41ba87` → 合并 `e2df025`）：① core `CoreSimos.bootstrapGenesis(SimulationState)`——**唯一绕过 `submit` 的写路径、只在空库**（S8 的记录偏离；M4 Task 17"世界创建无主"在此收口）；② app `DemoWorld`（确定性走廊三格 desert + `u-1` + 人口 15000）+ `ShellMain --demo` 空库首启 + 可点击 URL；③ `webui/map.js` 叠加 `/api/units` **单位标记**。测试 +8（`CoreSimosTest` +3 / `DemoWorldTest` +5）；**2 轮变异 0 存活**（m1 不写创世 checkpoint ⇒ `replay((main,1))` 红；m2 tick 写 0 ⇒ 行时刻断言红）。合并后主树门禁 **789** 条 = 170/255/45/131/**150**/**38**、`BugInstance size is 0` ×6、`[ERROR]` 0。
★ **控制器视觉核验（读 `map-unit-selected.png`）**：`u-1` 标记画在 (1,1)；点击后右栏 `terrain=desert / height=0.5`、**facets 表 `unit 第一册 unit:u-1` 与 `social 1_1 Population 15000`**；底部"1 位单位"。——"世界真的可看"成立。

**裁定 61**：
1. **S8 偏离获准**：`bootstrapGenesis` 是第二条写路径，但**世界创建先于命令面存在**（无 base/handler/变更集可提交）⇒ 必须有一条 bootstrap；护栏 = **仅空库 + 二次调用必抛**（有测试）。**spec §S8 已回填**（"只读"读作"除创世外只读"）。
2. **T9b 是计划外任务**（控制器在 T9 关账时裁定新增）——计划 §四汇总表已加一行。
3. 前端标记/选中态与 `map.html` 一行提示文案——接受。
★ 另记：执行者实测本机 `nproc=8`（CLAUDE.md 旧记 `nproc=2`）——旧并发上限至少在本机已不成立；**仍沿用"一次只跑一个 Maven"**。

**下一批**：Batch E = **T5（工具集）‖ T6（审批装配）→ T7（MCP 服务）**。

---

### Batch E-1（T5 工具集 ‖ T10 AgentBinding）已关账（2026-09-19 06:1x，子代理并行 + 控制器核验）

**T5**（`m5/t5` `a62bb6a` → 合并）：9 读 + 3 写 + `SimosToolSource` + Shell 绑定（并暴露 `toolRegistry()` 给 T7）；11 条测试；**2 轮变异 0 存活**（m1 R5 catalog 少列一个 type ⇒ 红在 catalog 完备性；m2 R4 initiator 写死 `player:local` ⇒ 红在**独立 store 读回**的身份断言）。任务内全量 800（app 33→49）。
★ **门禁又抓到 5 个真项**：4×`EI_EXPOSE_REP2`（工具构造器存 `CoreSimos`）+ 1×`EI_EXPOSE_REP`（`Shell.toolRegistry()`）——均已修。**"护栏真的会响"再次兑现。**

**T10**（`m5/t10` `03e5a5f` → 合并 `b2dedd2`）：util `AgentAttachPolicy` + map/unit 两条模块策略 + app `binding/*`（记录族 + `BindingRegistry`）；12 条测试；**1 轮变异 0 存活**（m1 删整段策略咨询 ⇒ **4 条断言同时红**，含"**拿 canonical 地址问策略、而不是 Human 形**"）。任务内全量 801（app 38→50）。

**合并后主树门禁**：`clean verify` rc=0、**812** 条 = 170/255/45/131/150/**61**、`BugInstance size is 0` ×6、`[ERROR]` 0。

**裁定 62**：
1. **map 策略从「Region 且 `type=Nation`」降级为「任意存在的 `Region`」**——实测 M2 的 `Region`/`RegionMeta` **没有 `type` 字段**（`RegionMeta(color,tag,description,annexedBy)`）；判定委托 `MapResolver`（`typeName == "Region"`）。⇒ **取代说明**：总纲 §5.5 的举例应读作"**由各模块自己声明**"，具体判据随模块真实形状落地（不发明字段）。
2. **`BindingRegistry` 纯内存、不持久化**（spec §6 只要求"记录 + 可绑性 + 查询"）；"绑定是否跨进程存活"**未裁决**，M5 不做——如实记（T10 报告 §4/§5）。
3. T5 的 `SimosToolSource` 构造签名照派单；`Shell` 新增 `toolRegistry()`（T7 的输入）。

**下一批**：Batch E-2 = **T6（审批装配）**——**串行**（改 `Shell` + T8 留的审批代理口）。

---

### Batch E-2（T6 审批装配）已关账（2026-09-19 06:2x，子代理 + 控制器核验）

**T6**（`m5/t6` `337c9f7` → 合并 `3957a1a`）：`Shell` 装配审批链（`PendingApprovals` → `HttpApprovalChannel` → `ApprovalCoordinator`（`AutoApproveGate`+`ConfirmGate`）→ `ToolCallAuthorizer.of(guard, coordinator)`（**非** `standard()`）；`ApprovalHttpEndpoint.start(config.approvalPort())` 成功后 `markUp()`；close 次序 GUI → 端点 → 通道 → Core）；`GuiServer` 把 T8 的 503/501 缝换成**真代理**（原始字节透传 状态码/体 + `Content-Type`/`Allow`；不可达 ⇒ 502）；`ShellApprovalTest` 3 条 + `GuiApiTest` 调整；**1 轮变异 0 存活**（m1 写工具 `Ask→Allow` ⇒ 红在 R3 断言「写工具必须先进审批」）。任务内全量 815（app 61→64）。合并后主树门禁 **815** = 170/255/45/131/150/**64**、BugInstance 0 ×6、ERROR 0。

**裁定 63 —— T6 的四处实测校正（全部接受）**：
1. **`callerKey` 是 `AccessToken` 桶名**（`GUEST`/`DEFAULT`/`SYSTEM`），**给不出 `player:gui`**——我的派单文字有误；实测收窄证据 = 代理回执 `scope=="once"`（调用者桶 `DEFAULT`）。spec §九若写"审批按 `player:gui` 归类"需校正（T6 报告 §四.1 在案）。
2. `pendingApprovals()` 的 `EI_EXPOSE_REP` 抑制**不必要**（加了反被 `US_USELESS_SUPPRESSION_ON_METHOD` 判红）⇒ 已撤；**不得**据此推断 `PendingApprovals` 不可变（形态 6 同源）。
3. GUI 绑定失败的端点回滚**不能写显式 `throw e;`**（`THROWS_METHOD_THROWS_RUNTIMEEXCEPTION`）⇒ 改 `finally` 无显式 throw。
4. 审批等待上限 **5 分钟**（spec 未定值；到点 fail-closed 归 AgentLib）。**未验**：超时真到点 / `APPROVE_SESSION` 真落会话键（需 `SYSTEM` 桶）/ **MCP 入口经同一 authorizer**（T7 验）。
★ **带裁定的遗留**：`ShellMain` 仍打印 `config.approvalPort()`（不在派单文件集内、未改）；`port=0` 时与实际不符——无害（缺省 5713 一致），记着。

**下一批**：**T7（5715 MCP 服务装配）**——**最后一个实现任务**（← 之后 T11 判据端到端、T12 关账）。

---

### Batch E-3（T7 MCP 服务装配）已关账（2026-09-19 06:3x，子代理 + 控制器核验）

**T7**（`m5/t7` `7f9322b` → 合并）：`Shell` 在装配第 6 步 `AgentToMcpServer.startHttp("127.0.0.1", config.mcpPort(), config.mcpPath(), toolRegistry, "simos-shell", …, mcpCaller(), toolAuthorizer())`（**必带审批 authorizer**）+ `boundMcpPort()` + close 次序 GUI → **MCP** → 端点 → 通道 → Core；`McpServerTest` 4 条（**官方 SDK 客户端** `McpClient.sync` + `HttpClientStreamableHttpTransport` 走真 socket）：`initialize` / `tools/list` **exactly 12** / 读工具与 `QueryService` **逐值对拍** / **写工具经 MCP 触发审批**（DENY ⇒ `APPROVAL_DENIED` 且无 revision；APPROVE_ONCE ⇒ 提交 + `initiator` == `config.mcpInitiator` + 改名经 MCP 读回）；`AgentLibAvailabilityTest` 扩钉 3 类（+3 用例）。**2 轮变异 0 存活**（m1 末参换 `standard()` ⇒ 绕过审批；m2 caller 桶降为 `GUEST` ⇒ 硬拒先于审批——两者都红在 `McpServerTest:232` 的"**写工具必须先进审批**"断言，如实记：**同一断言**）。任务内全量 822（core 150→153、app 64→68）。合并后主树门禁见下。

**裁定 64 —— T7 的两处实测校正（全部接受）**：
1. ★ **spec §3.2 的 MCP caller 桶写错了**：`GUEST` → **`DEFAULT`**。三条写工具是 `ToolSpec.level(DEFAULT, sensitive=true, …)`，`PermissionChecker` 对级别不足**硬拒（不进审批）**⇒ `GUEST` 桶下**写工具全部不可达**（MCP 只能读不能写，与 S3/S4 的工具面设计直接矛盾）。`DEFAULT` 是**满足全部 12 条工具的最小桶**；权限集 `AgentPermissionSet.unrestricted(DEFAULT)`。**放行 ≠ 免审批**（authorizer 仍带 coordinator）。**spec §3.2 已回填**，计划汇总表加行。
2. **R2 按"M5 无内部工具"存档（不发明工具）**：12 条工具 `noExport()` 全 `false`；`McpServerTest` 用 `tools/list` **exact-set == 12** 作其 M5 形态；`noExport` 机制本身的判别力归 AgentLib 自己的用例。**如实记"R2 未自证"**（spec §11 允许存档）。
★ 另记（未验，归 T12 或存档）：caller-owned `startHttp` 形态未用（用 owned，spec §7.2 点名）；MCP 官方客户端首连 405/降级行为未细察；跨机/网络 MCP 未测。

**下一批**：**T11（判据①②端到端 + R9 生命周期）**，然后 **T12（M5 关账）**。

---

### Batch E-4（T11 判据端到端）已关账（2026-09-19 06:4x，子代理 + 控制器核验）

**T11**（`m5/t11` `e130d67` → 合并 `559c6f2`）：三个**新用例类**（test-only，零 main 改动）——`ShellEndToEndTest`（判据①：GUI 写 `(main,2)` / MCP 写 `(main,3)` / MCP advance `(main,4)` **同表**、`initiator` `player:gui` vs `agent:t11-e2e` 逐字不同、链 2/2/5 完整、世界逐值）、`McpCoverageTest`（判据②：catalog 8 类型逐类 committed `main@2..9` + `advance` `main@10` + `fork` `mcp-branch@1`；反向坏载荷 ⇒ `REJECTED` 且行数不变）、`ShellLifecycleTest`（R9：三端口可重绑 + 非守护线程差为空）；**3 轮变异 0 存活**（m1 R9 去掉 `mcpServer.close()`；m2 R4 `player:gui→player:local`；m3 R5 `DisbandUnit` 返 `UNSUPPORTED`）。任务内全量 **825**（app 68→71）。合并后主树门禁 **825** = 170/255/45/131/153/**71**、`BugInstance` 0 ×6、`[ERROR]` 0。

**裁定 65 —— T11 的两处口径校正（接受）**：
1. ★ **"冻结 6 事件"是派单措辞错，不是代码缺陷**：我（控制器）在 T11 派单里写"assert its full frozen 6-event sequence"，把 M4 文档里 **N=2 的举例**当成了常量。R6/spec §1.1 的原文是 `received → started → **N×proposal** → finished → committed`，`N = 注册的 time participant 数`；本壳只注册 1 个（`UnitTimeParticipant`）⇒ **实测 5 条**，用例按 5 条断言。**spec 无需改**（spec 本来就写 N×proposal）。
2. **判据②执行序 = 语义合法序，非 catalog 字典序**：字典序把 `CancelRoute` 排在 `DisbandUnit` 前，而 `DisbandUnit` 会移除 `u-1` ⇒ 后续命令"查无此人"。用例改为自定义合法序，另用 `MINIMAL_PAYLOADS.keySet() == catalogTypes` 钉住"每个 catalog 类型都有载荷"。**R9 单独立类**（`ShellLifecycleTest`，派单允许）。

★ 如实记（T11 报告 §七）：反向用例绝对行数**未打印**（推导 11）；R9 线程名未枚举（有判别力的是端口释放那一半）；R9 未覆盖"审批长连在途时关壳"；`simos.fork` 不发事件 ⇒ 判据①未断言分岔行的链。

---

## T12 M5 关账（2026-09-19 06:5x，控制器内联）

**M5 = 12/12 完成**。关账四条判据逐条核过：

1. **判据① 逐条实测值**：`ShellEndToEndTest` 三行同表 + initiator 逐字不同 + 链 2/2/5 + 世界逐值（本报告 §一）。
2. **判据② 逐条实测值**：`McpCoverageTest` catalog 8 类型逐类 committed + advance + fork + 反向 REJECTED 无 revision（本报告 §一）。
3. **主树全量门禁绿**：`./mvnw clean verify` rc=0、**825** 条 = 170/255/45/131/153/**71**、7/7 模块、`BugInstance size is 0` ×6、`[ERROR]` 0（`t12-evidence/merged-full-verify.log`）。
4. **R1~R9 点验**：8 条有变异自证（含 R3/R4/R5 各两条独立载体），**R2 存档**（M5 无内部工具，如实记偏离）；12 个任务累计 **21 轮变异 0 存活**（本报告 §三）。

**裁定 66 —— M5 关账结论与遗留条目**：判据①②闭合；`task-12-final-report.md` 落盘（含"我未能核实的"清单）。**带裁定的遗留 11 条**（R2 存档 / `ShellMain` port 打印 / `BindingRegistry` 不持久化 / `PlanRoute` 稀疏 waypoints / 审批超时与会话键未验 / fork 不发事件 / R9 长连分支未覆盖 / 反向行数未打印 / 重放代价未测 / **运行形态评测=够用但不便、shade 归开口项** / §十三 1~3 已消）。**M5 完成，下一里程碑 M6**（开工前先裁决 M6 待决项）。
