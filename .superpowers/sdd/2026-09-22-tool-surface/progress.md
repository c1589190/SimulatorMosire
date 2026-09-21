# SDD ledger — plan: docs/superpowers/plans/2026-09-22-tool-surface-plan.md

> 宗旨：`docs/superpowers/specs/2026-09-22-tool-surface-creed.md`（**spec，绑定权威**）
> 工作区：worktree `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1`，分支 `ts/m1`，基线 `5c090ce`
> **M1 BASE = `1fca612`**（开工提交：本台账）⇒ 审查包 = `1fca612..M1-HEAD`
> 证据目录：`.superpowers/sdd/2026-09-22-tool-surface/m1-evidence/`
> ★ 本机 `nproc=2` ⇒ **一次只准有一个重活**（Maven 与子代理不并存，已实测会杀掉 agent）

## 〇. 开工前裁定（用户 2026-09-22 当场裁定，取代计划 §四 的"倾向"列）

| # | 裁定 | 与计划的差异 |
|---|---|---|
| **D-1** | unit 20 条**也给决策人桶** | ★ **与计划倾向（不给）相反** ⇒ 按用户裁定执行（unit 工具是**窄工具**、非通用写，与 N9「决策人无通用写」不冲突） |
| **D-2** | `sd.SetDecisionMakerProvider` **给 GM** | 与倾向一致 |
| **D-3** | 读口**先列清单再定** | 与倾向一致 ⇒ **M4 的派单前必须先产出清单并回用户** |
| **D-4** | 通用写收窄 = **只评估** | 与倾向一致 |
| **D-5** | 导入器 tag 改 `nation:<名>` **并入本阶段** | 与倾向一致 ⇒ 并入 M3，且**需重导/复验真档** |

## 一. 开工前冲突扫描（每对共享文件的任务一行 + 每任务自洽一行）

### 1.1 任务对（共享文件 / 接口）

| 任务对 | 共享点 | 一方的产出 vs 另一方的消费 | 结论 |
|---|---|---|---|
| **M1×M2** | `SimosToolSource.java` 的写桶构造点；`McpCoverageTest`；工具计数断言 | M1 的 7 条与 M2 的 20 条加进**同一个** `List<AgentTool>` 构造点 | **冲突 ⇒ 串行** |
| **M1×M3** | 同上（`addGmWrites`）；`CatalogTool.PAYLOAD_HINTS` | 同 | **冲突 ⇒ 串行** |
| **M2×M3** | 同上 | 同 | **冲突 ⇒ 串行** |
| **M1/M2/M3×M4** | `SimosToolSource.readTools`（9 条读工具**四桶共享**）+ 各桶既有断言 | M4 加读工具会牵动 M1~M3 刚落定的桶断言 | **冲突 ⇒ 串行，且 M4 排最后** |
| **M5×其余** | 无代码改动（只评估） | — | **独立**，最后做 |

★ **扫描结论：M1~M4 全部串行**（计划 §二写的"组内可并行"**不成立**——四组都改同一批文件）。
★ 这也符合本仓通则：**同一文件被两个任务改 ⇒ 串行**。

### 1.2 任务自洽（计划文本 vs 实读代码/裁定）

| 任务 | 计划文本 | 实测/裁定 | 处置 |
|---|---|---|---|
| **M1** | §三.4「连带：`CatalogTool.PAYLOAD_HINTS` 补一条」 | 7 条 map 提示**已在表内**（`CatalogTool.java:57-64`）⇒ **对 M1 是空操作** | 记录，不派无用工作（`McpCoverageTest` 面待 recon 结论） |
| **M1** | §一「map 写 现有工具 **0**」 | 待 recon 核实 | — |
| **M2** | §二 M2「★ 待裁 D-1：倾向不给」 | **用户裁定：也给** | 按裁定执行；本台账为准 |
| **M3** | §二 M3「`SetDecisionMakerProvider` 是否开给 GM？（倾向开）」 | 用户裁定：开 | 按裁定执行 |
| **M3** | §四 D-5 并入 = "M3 的前置消除" | 实际是 **Python 导入器改动**（`tools/`，不入 Maven reactor）+ 真档复验 | 记为 M3 的子项，独立判据 |
| **M4** | §二 M4 要求"逐条列出缺哪些" | 用户裁定 D-3 = 先列清单再定 | **M4 的派单前必须先交付清单并回用户**（阻塞点，非我能自裁） |
| **M5** | 只评估不改 | 同 | 最后做，产出评估报告 |

## 二. 任务台账

（每任务一行：`Task <N>: complete (commits <base7>..<head7>, review clean)` 或 fix round 行）

### Task 1 — M1 地图组（7 条写工具）
- 状态：**in progress**（简报已落 `task-1-brief.md`，实现者已派）
- BASE：`1fca612`

#### 1.1 recon 取证结论（只读子代理，未跑构建）

**载荷形态**：7 条命令全注册在 `simos-app/.../app/Shell.java:306-313`，实现在 `simos-map/.../map/spi/`。
`SetEdge.mode` 与 `RandomizeRegion.seed` **无默认值**（各自 javadoc 明文）。

**域层既有守卫**：用户点名的 6 项**全部存在**且文案可读（`未知地形类型: `/`未知连通性类型: `/
`区域已存在: `/`区域不存在: `/`hexes 不得为空…`/`color 必须是 #RRGGBB…`）。**没有一条静默成功。**

**★ 静默缺口 7 处（域层，全部为读码结论、未运行验证）**：

| # | 缺口 | 锚点 |
|---|---|---|
| A | 通路组 id **大小写变体**：注册侧 `containsKey`（敏感），解析侧 `resolveKind` 不敏感**且取首个** ⇒ 已有 `river` 时注册 `River` 成功，此后 `kind:"River"` 落到先注册的 `river`；两条路径都不报错 | `PathwayGroupOperations.java:38-39` vs `EdgeOperations.java:116-124` |
| B | `map.SetEdge` **无邻接校验**（可连相隔半图两格） | `EdgeOperations.java:59-75` + `GameMap.java:72-89` |
| C | `map.UpdateRegion` 的 `meta` 是**整体替换**：只给 `{"tag":"X"}` 静默清掉 color/description/annexedBy；`"meta":{}` 非 null 却**过**「至少给一个」检查并把 4 字段全清空 | `RegionOperations.java:83,93,96` + `MapPayloads.java:136-150` |
| D | `kind`/`mode` 大小写不敏感（`"REPLACE"` 被接受） | `EdgeOperations.java:116-130` |
| E | 未知多余字段**静默忽略**（可选字段拼错 ⇒ 静默取缺省，如 `visble` ⇒ `visible=true`） | `MapPayloads.java:44-47`；★ recon 自陈**未核实**是否有全局 Jackson 严格配置 |
| F | **空操作仍落 revision**（`MapChangeSet.isEmpty()` 存在但 `CommandBus` 从不读） | `MapChangeSet.java:107` vs `CommandBus.java:320-332,347-378` |
| G | `PropertyDef.type` 不做取值白名单（有意，javadoc 明说） | `PathwayGroup.java:73-77` |
| H | `map.CreateRegion` **无 name 唯一性校验** | `RegionOperations.java:57` |

**爆炸半径 4 处**：`SimosToolsTest.java`（名单 16→23 + `.hasSize(16)`→23）、`McpServerTest.java`（名单 16→23）、
`McpPortTopologyTest.java`（`GM_NARROW_WRITES` 4→11）、`AppWritePathGuardTest` 的禁字（`SqliteStore`/`Timeline`/`CheckpointStore`）。
**零改动**：`CatalogTool.PAYLOAD_HINTS`、`McpCoverageTest`（7 类型已在双向断言与最小载荷表内）。

**GUI 侧第二源**：**没有** per-command 端点，7 条全走 `POST /api/command`；第二载荷源是 webui JS
（`map.js` 各构造点已取证）。★ **`map.RegisterPathwayGroup` 在 webui 里没有载荷构造** ⇒ 无第二源可对拍。

#### 1.2 控制器裁决（本任务）

- **裁决 A**：**M1 不加工具层前置校验**。理由：① 域层 7 条全部已有可读拒绝文案，经 `ToolSupport.fold`
  已变成 `ToolResult.error("REJECTED", <原文>)` ⇒「可读理由到达调用方」**已成立**；② 工具层校验
  **可被 `simos.command.submit` 绕过** ⇒ 是装饰（违反本仓「护栏必须自证」）；③ 计划 §三.2 的用意由 fold 兑现。
  ⇒ 本任务的义务是**证明拒绝理由真的到达调用方**（判据 4：7 条各一条坏载荷用例）。
  *代价若判错*：若用户本意是"工具层也要挡一道"，则要在 7 条里补校验并重跑变异轮。
- **裁决 B**：**A~H 七处域层静默缺口不在 M1 修**。理由：① 它们是**既有的**——今天经通用写
  （`simos.command.submit` 已在 EXTERNAL∪GM 桶里）就可达，**不是 M1 新造的**；② 改域层会牵动已关账的
  M2/M8 变异轮（裁定 42：改动被测文件 ⇒ 旧证据作废、须重跑）；③ 代价远超本任务价值。
  ⇒ **记为开口项**，其中 **A / B / C** 三处**用户可见**，随 M1 报告一并回报。
  *代价若判错*：缺口继续存在；用户若要修，另立任务。
- **裁决 C**：★ **`SimosToolsTest` 的 `subList` 索引切片是真缺陷，M1 必须修**。
  读闸 `subList(0,9)` / 写闸 `subList(9,16)`：名单变长后切片仍合法 ⇒ **测试照绿，新增 7 条写工具完全不被
  写闸覆盖**（「把没发生伪装成没发生」族）。修法：改成**按名单选**，且断言语义改为
  **「写闸覆盖集 == 写工具全集」** ⇒ 这样"退回切片"才**杀得掉**（否则修复自身无护栏）。
  *代价若判错*：无——这是净收益。
- **裁决 D**：7 条专归 **GM 桶**（照计划；`EXTERNAL_WITH_GM` 自动含 GM，不动 `addExternalWrites`/
  `addDecisionAgentWrites`）。map 写**不进决策人桶**（D-1 裁定的是 **unit** 20 条）。

