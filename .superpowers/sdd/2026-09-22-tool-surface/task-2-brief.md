# Task 2 简报 — M2 unit 组（20 条窄写工具，GM + 决策人双桶）

> 控制器交给你的**唯一需求来源**。下面的文件名/类名/字符串/行号**逐字照用**。
> 行号会漂移：**先按名找，行号只当提示**。
> ★ 你的**事实附件**是 `.superpowers/sdd/2026-09-22-tool-surface/m2-recon.md`（只读取证，20 条清单、载荷真值表、
> 域层拒绝文案、桶追加点、爆炸半径都在那里）。**本简报是需求，那份是事实**——冲突时以**本简报**为准并当场报分歧。

## 0. 你在哪 / 干什么

- 工作区：**worktree `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1`**（分支 `ts/m1`）。★ **本仓同时有主检出与 worktree，同名文件各一份 ⇒ 改文件一律用 worktree 内的绝对路径。**
- 项目：SimulatorMosire（simos），模块化仿真引擎。
- **你要做的**：把 unit 域的 **20 条写命令**各包一个**窄写工具**，**同一批工具挂进两个桶**：
  **GM 桶** 与 **决策人（DECISION_AGENT）桶**（用户裁定 D-1：「unit 20 条**也给决策人**」——
  依据是它们是**窄工具**、不是通用写，与 N9「决策人无通用写」不冲突）。
- 前置：**M1 已完成**（map 域 7 条工具，同样的形制）。**照抄 M1 的形制，不要发明。**

## 1. 形制 —— 照抄这些文件，不要发明

- 基类（**同包、package-private，你在同包内可直接继承，一个字都不要改**）：
  `simos-app/src/main/java/io/mosire/simos/app/tools/write/AbstractNarrowWriteTool.java`
- 模板（M1 刚落的 7 个）：
  `simos-app/src/main/java/io/mosire/simos/app/tools/write/MapSetTerrainTool.java` 等
- 基类已钉死：schema（`payloadJson`/`branch`/`expectedRevision`，required 只有后两者）、
  `spec()` = `ToolSpec.level(AccessToken.DEFAULT, true, false)`（`sensitive=true` ⇒ 走审批门链）、
  `gate()` = `ToolGate.Ask(name(), summary, AskKind.SENSITIVE)`、`resources()` = `ToolSupport.ALL_WRITE`、
  `execute()`（组信封 → `core.submit` → `ToolSupport.fold`）。

你**每个类只写**：构造器 + `commandType()` + `summary(args)` + `description()` + **中文类 Javadoc**。

## 2. 要建的 20 个类

包：`io.mosire.simos.app.tools.write`。**类名 = `Unit` + 命令名去掉冗余的尾部 `Unit`**（下表**逐字照用**，不要自己推）：

| # | 类名 | `NAME` | `description()` 返回串（**逐字照用**） |
|---|---|---|---|
| 1 | `UnitRenameTool` | `unit.RenameUnit` | `"单位改名：固定 unit.RenameUnit，载荷 {id, name}"` |
| 2 | `UnitCreateTool` | `unit.CreateUnit` | `"新建单位：固定 unit.CreateUnit，载荷 {id, name, position{q,r}, member, equipment, speed, mobilityPerMille, parent?, status?（缺省 MOVING）}"` |
| 3 | `UnitReparentTool` | `unit.ReparentUnit` | `"改单位的父：固定 unit.ReparentUnit，载荷 {id, parent?}（★ parent 缺省或为 null = 清根，不是“不动”）"` |
| 4 | `UnitSetStrengthTool` | `unit.SetStrength` | `"设定单位兵力：固定 unit.SetStrength，载荷 {id, member, equipment}"` |
| 5 | `UnitPlaceAtTool` | `unit.PlaceAt` | `"瞬移单位：固定 unit.PlaceAt，载荷 {id, hex?}（★ hex 缺省或为 null = 撤销位置，不是“不动”）"` |
| 6 | `UnitPlanRouteTool` | `unit.PlanRoute` | `"下达路线：固定 unit.PlanRoute，载荷 {id, waypoints[{q,r}…]}（至少两个路径点）"` |
| 7 | `UnitCancelRouteTool` | `unit.CancelRoute` | `"取消路线：固定 unit.CancelRoute，载荷 {id}"` |
| 8 | `UnitDisbandTool` | `unit.DisbandUnit` | `"解散单位：固定 unit.DisbandUnit，载荷 {id}（仍是链的 commander/成员、或仍有下属时会被拒；★ 不检查悬空的回归目标）"` |
| 9 | `UnitSetStatusTool` | `unit.SetStatus` | `"设定单位状态：固定 unit.SetStatus，载荷 {id, status（MOVING\|RESTING\|ENGAGED）}"` |
| 10 | `UnitAttachTool` | `unit.AttachUnit` | `"合体（同格前提下重新挂到父）：固定 unit.AttachUnit，载荷 {id, parent?}"` |
| 11 | `UnitDetachTool` | `unit.DetachUnit` | `"脱离父：固定 unit.DetachUnit，载荷 {id}（已是根单位会被拒）"` |
| 12 | `UnitReparentSubtreeTool` | `unit.ReparentSubtree` | `"整树改挂：固定 unit.ReparentSubtree，载荷 {rootId, parent?}（★ parent 缺省或为 null = 提升为根，不是“不动”）"` |
| 13 | `UnitSetFormationOffsetTool` | `unit.SetFormationOffset` | `"设编制偏移：固定 unit.SetFormationOffset，载荷 {id, dq?, dr?}（★ 两者全缺 = 清除偏移；只给一个分量时另一个按 0）"` |
| 14 | `UnitSplitFormationTool` | `unit.SplitFormation` | `"拆分编制：固定 unit.SplitFormation，载荷 {rootId, subUnitIds[]（不得为空）}"` |
| 15 | `UnitMergeFormationTool` | `unit.MergeFormation` | `"合并编制：固定 unit.MergeFormation，载荷 {childId, parentId}（★ 必须同格且 child 状态为 MOVING）"` |
| 16 | `UnitPlanSparseRouteTool` | `unit.PlanSparseRoute` | `"下达稀疏路线：固定 unit.PlanSparseRoute，载荷 {id, waypoints[{q,r}…]}（逐段展开；任一段不可达即整条被拒）"` |
| 17 | `UnitSetRejoinTargetTool` | `unit.SetRejoinTarget` | `"设回归目标：固定 unit.SetRejoinTarget，载荷 {id, target?}（★ target 缺省或为 null = 清除回归意图）"` |
| 18 | `UnitCreateCommandChainTool` | `unit.CreateCommandChain` | `"建命令链：固定 unit.CreateCommandChain，载荷 {chainId, name, commander, members[]}"` |
| 19 | `UnitUpdateCommandChainTool` | `unit.UpdateCommandChain` | `"改命令链：固定 unit.UpdateCommandChain，载荷 {chainId, name?, commander?, members?}（★ 三者全缺是合法的，但会落一条 revision）"` |
| 20 | `UnitApplyCasualtiesTool` | `unit.ApplyCasualties` | `"施加战损：固定 unit.ApplyCasualties，载荷 {id, personnel（负增量）, equipment{键:负增量}}（★ equipment 必填——只报人员战损也要显式给 {}；未知装备键会被拒，不视作 0）"` |

`summary(args)` 一律照 M1 的形态（把中文短语换成各工具自己的动作，例如 `RenameTool` 用「改单位名」）：

```java
  @Override
  protected String summary(Map<String, Object> args) {
    return "改单位名 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }
```

★ **`description()` 里那 4 处「缺省 ≠ 不动」的警告是承重内容**，不许简化掉——它们是**给模型看的**，
少了模型会把"清根/清偏移/撤销位置"当成"不动"来用（`MapUpdateRegionTool` 的 `meta` 警告同族）。

## 3. 接线：**两个**桶

文件 `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`。
★ **两处都追加在方法体末尾**（顺序无判据依赖，但**不要**插到既有工具前面）：

- **追加点 #1（GM 桶）**：`addGmWrites(...)` 末尾（现以 `MapRegisterPathwayGroupTool` 收尾）→ 追加 20 条。
  ★ 追加后 **GM 桶 = 40 条**。
- **追加点 #2（决策人桶）**：`addDecisionAgentWrites(...)` 末尾（现以 `SubmitVerdictTool` 收尾）→ 追加**同样这 20 条**。
  ★ 追加后 **决策人桶 = 31 条**（9 读 + 2 sd 窄写 + 20 unit）。
- **`EXTERNAL_WITH_GM` 不用动**：它是 `addExternalWrites(...) + addGmWrites(...)` 的**并集**，**自动含**你的 20 条
  ⇒ 追加后 **43 条**。★ 这也解释了 M1 的一个现象：在 `addExternalWrites` 与 `addGmWrites` **之间**搬运工具
  **不改变并集** ⇒ 断言并集的用例**结构上看不见**桶错位。
- **`addExternalWrites(...)` 一个字都不要动**（决策人与 GM 都不含通用写 ⇒ `EXTERNAL` 桶恒 **12** 条不变）。

## 4. 前置即错 —— 本任务**不加**工具层前置校验（与 M1 同裁决，照办）

**裁决：20 条命令的域层已有可读拒绝文案 ⇒ 工具层不重复校验。** 理由与 M1 逐条相同：
1. 域层拒绝经 `ToolSupport.fold` 变成 `ToolResult.error("REJECTED", <域层理由原文>)` ⇒ **可读理由已到达调用方**；
2. 工具层的校验**可被 `simos.command.submit` 绕过** ⇒ 那是装饰（「护栏必须自证」）；
3. ★ **技术上也做不到**：载荷解析器 `UnitPayloads` 是 `simos-unit` 的**包级私有**，`simos-app` 的工具层**看不见它**。
   ⇒ **不许**在工具层复述载荷校验。

⇒ **你的义务是"证明拒绝理由真的到达调用方"**（判据 §5.4），不是"再写一遍校验"。

**下面这些既有行为控制器已裁「不在 M2 修」，你也不要顺手修**（它们是**既有的**、今天经通用写就可达，
改它们会牵动已关账的 M2/M8/unit-ext 变异轮——裁定 42：改动被测文件 ⇒ 旧证据作废、须重跑）：
未知/拼错字段被静默忽略；空操作仍落 revision；`unit.CreateUnit`/`RenameUnit` **不拒重名**；
`unit.DisbandUnit` **不检查也不清理悬空的 `rejoinTarget`**（运行期兜住）；`modes.js` 写白名单与后端工具面**无同源判据**。
（逐条锚点见 recon §2-D 与 §六。）

## 5. 判据（必须逐条有实测值）

1. **桶正确**：20 条新工具**同时**出现在 `GM` 与 `DECISION_AGENT` **两个**桶里；
   也在 `EXTERNAL_WITH_GM` 里（并集自动含）；
   **不出现**在 `EXTERNAL` 桶里。四桶规模实测应为 **EXTERNAL 12 / GM 40 / DECISION_AGENT 31 / EXTERNAL_WITH_GM 43**。
2. **名字同源**：每条工具的 `name()` == 其固定命令类型；20 个类型都已在 `catalog` 里。
3. **敏感写**：20 条 `spec().sensitive()` 为真、`gate()` 是 `AskKind.SENSITIVE` 的 `Ask`、`classKey == name()`。
   （继承基类即自动满足——但要**真的被断言覆盖到**，见 §6 的常量扩法。）
4. **★ 前置即错（本任务的核心判据）**：20 条工具**各一条**"坏载荷 ⇒ 可读 `REJECTED` 且 head 不变"的用例。
   用**域层已有文案**触发，断言里**要含该文案的关键片段**（「只判 token 判不出是哪一层拒的」是本仓反复踩过的坑）。
   建议取用（逐字文案与锚点见 recon §2-B，**你可以自选更好的触发载荷，但必须来自 recon 的表**）：

   | 工具 | 建议坏载荷 | 关键片段 |
   |---|---|---|
   | `UnitRenameTool` | 不存在的 `id` | `单位不存在` |
   | `UnitCreateTool` | 已存在的 `id` | `单位 id 已存在` |
   | `UnitReparentTool` | `parent` 指向不存在的单位 | `父单位不存在` |
   | `UnitSetStrengthTool` | `member` 为负 | `member 必须 ≥ 0` |
   | `UnitPlaceAtTool` | 不存在的 `id` | `单位不存在` |
   | `UnitPlanRouteTool` | 只给 1 个 waypoint | `waypoints 至少两个` |
   | `UnitCancelRouteTool` | 不存在的 `id` | `单位不存在` |
   | `UnitDisbandTool` | 单位仍是链的 commander | `仍是链` |
   | `UnitSetStatusTool` | `status:"NOT_A_STATUS"` | 见 recon §2-B（若该条文案不在表里，**当场取证后写实测文案**，不要猜） |
   | `UnitAttachTool` | `parent` 落在自己子树内 | `会成环` |
   | `UnitDetachTool` | 已是根单位 | `已是根单位` |
   | `UnitReparentSubtreeTool` | `parent` 落在自己子树内 | `会成环` |
   | `UnitSetFormationOffsetTool` | 不存在的 `id` | `单位不存在` |
   | `UnitSplitFormationTool` | `subUnitIds: []` | `subUnitIds 不得为空` |
   | `UnitMergeFormationTool` | 两个不同格的单位 | `只有同格才能合体` |
   | `UnitPlanSparseRouteTool` | 相邻段不可达 | `稀疏路线的段不可达` |
   | `UnitSetRejoinTargetTool` | `target` 是自身 | `回归目标不得是自身` |
   | `UnitCreateCommandChainTool` | 已存在的 `chainId` | `链 id 已存在` |
   | `UnitUpdateCommandChainTool` | 不存在的 `chainId` | `链不存在` |
   | `UnitApplyCasualtiesTool` | 未知装备键 | `未知装备键` |

   ★★ **每条各一个用例，不许写成一个循环里断 20 次**（循环里断 N 次时，变异杀掉一条其余照样绿，判别力被稀释）。
   ★★ **第 1 条 `unit.RenameUnit` 必须单独写**：它是**唯一不走 `UnitPayloads`** 的命令（自己的 `readTree`、
   两族不同文案）。若照抄其余 19 条的统一文案模板，你会得到一个**恒真的 token 断言**（T4/T10-b 那一族的坑）。
5. **★ 补一条同源强判据**（本任务新增的护栏，见 §6）：**扫源码**把 `tools/write/*Tool.java` 的 `NAME` 常量抽出来，
   断言 **"实现了窄写工具类的集合 == GM 桶的窄写工具集合"**。
   - **它要挡住的是**：写了 `write/UnitXxxTool.java` **却忘了接进任何桶** ⇒ 今天**没有任何断言会红**
     （工具面看不见它；两份手抄名单也不认识它）。这正是 M4 侦察点名的「同源判据只覆盖命令、不覆盖工具」那个缺口。
   - **形态**照 `SimosToolsTest.catalogCoversEveryCommandHandlerImplementation`（`handlerTypesFromSources()` 扫 `*Handler.java`）——
     **同一种做法**，只是对象从 `CommandHandler` 换成 `AgentTool`。
   - ★ 必须**先断言扫到的集合非空**（否则扫描器写错时会退化成"空 == 空 恒真"——本仓踩过，见纪律）。

## 6. 爆炸半径 —— 会红的 4 处 + 5 个常量，**全部**要你处置

★ 结论：**必红 4 处**，全部是"**精确集合相等**"型；**按名 `contains`/`doesNotContain` 型一律不红**。

**(a) `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java`**
- `EXTERNAL_UNION_GM_TOOL_NAMES`（现 **23** 条）⇒ **43**；`WRITE_TOOL_NAMES`（现 **14**）⇒ **34**。
- ★ **第 1 处必红**：`roleBucketsNeverCarryGenericWrite` 的 **`externalWithGm` 块里 `.hasSize(23)`** ⇒ 改 **43**。
- ★ **`writeFaceCoveredByTheWriteGate()` 不要动**——M1 刚把它从"索引切片"改成**从真工具面派生**，
  它会**自动跟上**新 20 条；这正是那次修复的价值。（**退回切片**就杀了它，M1 已有变异体。）
- ★ **不许再抄第一份名单以外的第三份**：`EXTERNAL_UNION_GM_TOOL_NAMES` 是 `union(READ, WRITE)` 的**派生式**常量就保持派生式，
  别改成手写 43 条。

**(b) `simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java`**
- 同形的 `EXTERNAL_UNION_GM_TOOL_NAMES`（现 **23**）⇒ **43**。
- ★ **第 2 处必红**：`initializeAndToolsListExposeExactlyTheExternalUnionGmTools`（真 SDK `listTools()` 对拍）。
- ★★ **这份是"手抄的第二份"**（与 (a) 逐条相同、都在 23）⇒ **必须两处同改**。

**(c) `simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java`**
- `GM_NARROW_WRITES`（现 **11** = 4 sd + 7 map）⇒ **31**；`DECISION_AGENT_WRITES`（现 **2**）⇒ **22**。
- ★ **第 3 处必红**：`existingPortExposesExternalUnionGmToolFace` 的精确匹配（现拼 `READ+ GENERIC + GM_NARROW`）。
- ★ **第 4 处必红**：`decisionPortExposesOnlyDecisionAgentToolFace` 的**正向**精确匹配（现拼 `READ + DECISION_AGENT_WRITES`）。
- 反向三条（`doesNotContainAnyElementsOf(GENERIC_WRITES)` / `doesNotContain("sd.SetViewScope")` /
  `doesNotContainAnyElementsOf(MAP_WRITES)`）**不会红**（unit 20 条既不在通用写、也不在 map 写里）——但请**跑一遍确认**，不要假定。
- ★★ **同一方法里的那三条负向断言要加注**（评审已确证，控制器裁决 J）：它们**在同一个方法内被上面那条
  `containsExactlyInAnyOrderElementsOf(...)` 严格遮蔽**（AssertJ 方法内首失败即止）⇒ **生产侧变异**下
  它们**永远不可能是唯一红点**。
  **但它们不是装饰**——那条精确匹配比的是**真实工具面 ⇄ 手抄常量**（证"代码 == 常量"），
  负向断言比的是**真实工具面 ⇄ 另一份独立维护的名单**（证"代码里没有 map 写 / 没有通用写"）。
  **两者只在"常量是对的"这个前提下等价**，而「工具进了桶 + 手抄常量被顺手同步」正是本仓在案的复发性失效模式。
  ⇒ **处置：保留，但必须加 `.as(...)` 注明它守的是什么**（例：「防御性：主要由上面的精确匹配遮蔽；
  独立捕获的是『工具进桶 + 手抄常量同步』这一两处同改的失效模式」）。**不许原样留着而不加注。**
  ★ 并跑 **§7 的 m4** 把这条声明变**可证伪**。
- ★ **顺带把该方法的 `.as()` 文案里的旧数字改对**（`9 读 + 2 窄写` ⇒ M2 后为 `9 读 + 22 窄写`）。

**(d) `simos-app/src/main/java` 的禁字约束**（不红，但新代码要遵守）
`AppWritePathGuardTest` 扫 `simos-app/src/main/java` 下全部 `.java`（**剥注释后**，但**字符串字面量不剥**）
禁止出现 `"SqliteStore"` / `"Timeline"` / `"CheckpointStore"`。
★ **保守做法：这三个词（含 Javadoc 里）干脆一个都不写**——剥注释器本身有被坑的历史。

**(e) 明确【不改】的四处**（recon 已证，别白干）
- `CatalogTool.PAYLOAD_HINTS`：43 条里 unit **20 条全在** ⇒ **零改动**。
- `McpCoverageTest` 的 `EXPECTED_COMMAND_TYPES` / `MINIMAL_PAYLOADS`：unit **20 条全在** ⇒ **零改动**。
  ★ 两者驱动量都是**命令类型**，而 M2 **不新增命令类型**（只加**工具**）⇒ 两张表都不动。
- `Shell.java`：20 条 handler **已全部注册** ⇒ **handler 零改动**（M2 只加工具、不加 handler）。
  ★ **但注释要改，且改法是"不钉数字"**（控制器裁决 I）：该类四处注释仍写
  「现有口 `EXTERNAL_WITH_GM` **16 工具 = 3 通用写 + 4 GM 窄写 + 9 读**，决策人口 `DECISION_AGENT`
  **11 工具 = 2 窄写 + 9 读**」（现 `:132`，另 `:164`/`:435`/`:691` 同病，**按串找不按行号找**）。
  M1 之后它已经是陈旧的（实为 23 = 3 + 11 + 9），M2 之后**又要变**（43 = 3 + **31** + 9；决策口 31 = **22** + 9）。
  ⇒ **不要改成新数字**（下一个任务还会让它们过期），改成**不随工具数漂移的表述**，
  例：`现有口 = 通用写 + GM 窄写 + 读工具，条数以工具面为准`。
  ★ 这是**纯注释改动、零行为**；`AppWritePathGuardTest` 的禁字约束不涉及这三个词，但 `Shell.java`
  里**已有**合法的 `SqliteStore`/`Timeline`/`CheckpointStore` 字样出现在注释中——**别去动它们**（剥注释器负责）。
- `webui/**`：**不改 JS**。★ 但**范围声明要照抄进报告**：这 20 条是 **MCP/agent 面**，
  **工作台（GUI）看不到也发不出**（`modes.js` 的 unit 白名单只有 6 条且 fail-closed）
  ⇒ **"全绿"不等于"界面上能用"**（T10-l 口径）。

## 7. 变异（≥3 条，每条都要**真的被杀**，且存活要如实报）

- **m1 决策桶漏一条**：把 20 条里任意一条**从 `addDecisionAgentWrites` 删掉**（GM 桶保留）⇒
  期望 **`McpPortTopologyTest` 的决策口正向精确匹配红**。★ 这条正是 M1 缺的那个靶子（M1 的 m1 在 GM/EXTERNAL 之间搬，并集不变 ⇒ 看不见）。
  ★★ **本变异还带一个判据责任**：M1 有一条负向断言（`SimosToolsTest` 的「决策桶不含 map 写」）**被控制器记成
  "结构性不可杀"**，评审者当场证伪（拷贝型变异即可杀它）。控制器的**可证伪触发条件**写在台账裁决 H 里：
  **若本变异没能让决策口的精确匹配红，必须立刻如实上报**（不许把"没红"解释成"结构性不可杀"）。
- **m2 名字错**：把任意一条工具的 `commandType()` 返回值改成**另一个已注册的 unit 类型** ⇒ 期望名字同源判据红。
- **m4 ★ 双侧自证（把 §6(c) 那条加注变可证伪）**：**同时**改两处——
  ① `SimosToolSource.addDecisionAgentWrites` 里加一条**已存在的** map 窄写（如 `new MapSetTerrainTool(...)`）；
  ② `McpPortTopologyTest.DECISION_AGENT_WRITES` 常量里**同步加上该名字**（模拟"工具进桶 + 手抄常量被顺手同步"）。
  ⇒ 期望 **`:130` 那条精确匹配绿、`:131-135` 那条负向断言红**（= 证明加了注的负向断言确有独立价值，不是装饰）。
  ★ 这**允许改测试文件**——本仓既有做法（M1 的 m3「退回索引切片」就是测试侧变异），不是放宽纪律。
  ★ 若实测**不可达**（例如常量同步后别的断言先红），**如实报"不可达"并给出红的理由**，
  **不许**为了凑一个红点而放松任何断言。
- **m3 ★ 孤儿工具**：新增一个 `write/UnitFooTool.java`（`NAME = "unit.NotARealCommand"` 或任一流派**合法但不接桶**），
  **不接进任何桶** ⇒ 期望 **§5.5 那条新同源判据红**。★ **这条是本次最重要的变异：它证明新护栏不是装饰。**
  （变异体跑完**必须删掉该文件**并恢复干净世界。）

变异纪律（本仓硬要求）：**按变异文件名（而非目标类名）拷入会让"红"变成编译错误 ⇒ 不算数**；
必须按**白名单**把变异体推成**目标类名**，并**强制断言日志里 `COMPILATION ERROR` 计数为 0**，
不为 0 就当场作废该轮。每轮开跑前把工作目录恢复成干净世界（重编原件、比 md5）。
**红的理由必须是被保护的那行本身**；**"没跑到" ≠ "没红"**（反应堆会在前面的模块红掉时短路，如实记）。

## 8. 门禁

- `./mvnw clean verify` **rc=0**；模块判据 **`SUCCESS [`** 8/8；`[ERROR]` **0** 行；
  `BugInstance size is 0` **×7**；前端 `[frontend-gate] OK … fail=0`。
- ★ **基线数字一律现场重算**（只取模块汇总行 `Tests run:` 相加），**不得引用任何文档里的现成数字**（含台账、
  含本简报）。M2 的参照 = **M1 关账树**：`170/369/45/259/179/141/251`（**你仍要自己重算一遍**）。
- ★ **本机 `nproc=2`**：一次只准有一个重活。**跑 Maven 时不要同时跑别的重活。**
- ★ **全量 `clean verify` 耗时压在 600 s 线附近**：前台起跑、给足超时；
  **"被杀"既不是红也不是绿**，如实记"第几次尝试"并留档不删。
- 迭代期只跑相关模块：`./mvnw -pl simos-app -am -Dtest=SimosToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- ★ 关账前**必须**跑过一次全量 `clean verify`（`mvn test` 不跑 SpotBugs）。

## 9. 证据 + 报告

- 证据目录：`.superpowers/sdd/2026-09-22-tool-surface/m2-evidence/`（**自己建**）。
  门禁日志、变异体与每轮日志、判据实测输出**都落这里**；★ **每份产物要自指**（把"这一轮跑的是哪份字节（md5）"追加进日志本身）。
- 报告写到 `.superpowers/sdd/2026-09-22-tool-surface/task-2-report.md`，含：
  1. 状态（DONE / DONE_WITH_CONCERNS / NEEDS_CONTEXT / BLOCKED）
  2. 提交（`git rev-parse HEAD` 的短 sha，一条或多条）
  3. 一行测试小结（跑了什么、多少条、结果、**第几次尝试**）
  4. **判据 §5 逐条实测值**（不是"已完成"，是**数字/输出**）
  5. **变异**：每个变异体 → 被杀 / 存活 + 红点在哪一行
  6. **§我未能核实的**（必填，没有就写"无"）
- ★ **不许改、不许提交台账 `progress.md`**（那是控制器的）。★ **不许改本简报与 recon 报告**。
- 返回给我的**只要**：状态、提交 sha、一行测试小结、concerns。**不要把报告正文贴回来。**

## 10. 全局约束（本仓硬规矩，违反会出事）

- ★ 绝不 `git add -A`；提交前先扫 `git diff --cached`。
- ★ **一个 key 都不许进代码**；值绝不进日志/异常/argv/env。
- ★ 不许 `pkill -f '<含端口/store 名的串>'`。
- ★ **不许改 `simos-unit` / `simos-map` / `simos-sd` / `simos-core`**（本任务**零领域改动**，只动 `simos-app`）。
- ★ 注释与文档用**中文**，与既有风格一致。中文 Javadoc 的折行交给 `./mvnw -q spotless:apply`，**不要手工调行宽**。
- **你是实现者，不要派子代理**（不派助手，更不派评审）。评审在控制器收到你的报告之后。
