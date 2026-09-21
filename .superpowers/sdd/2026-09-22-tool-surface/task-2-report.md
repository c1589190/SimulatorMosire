# Task 2 报告 — M2 unit 组（20 条窄写工具，GM + 决策人双桶）

> 需求来源：`.superpowers/sdd/2026-09-22-tool-surface/task-2-brief.md`（**简报是需求**）。
> 事实附件：`.superpowers/sdd/2026-09-22-tool-surface/m2-recon.md`（**recon 是事实**）。
> 证据目录：`.superpowers/sdd/2026-09-22-tool-surface/m2-evidence/`。工作树 `…/worktrees/ts+m1`（分支 `ts/m1`），基线 `40e19d2`。

## 1. 状态

**DONE_WITH_CONCERNS**

两处 concern 属"简报前提与世界不符"（§7 的 m2 字面形态**不可达**；§6(a) 把两份名单记成**派生式**而基线是**手抄**），
处置方式均为**如实披露 + 用等价强度的替代变异体补足判别力**，未放松任何断言；另有 1 处**我自己的取证产物数字写错**（§7「自纠」，已更正并留痕）。

## 2. 提交

| 用途 | 短 sha | 说明 |
|---|---|---|
| **实现提交（A）** | **`981586e`** | `feat(tool-surface): M2 unit 组 20 条窄写工具（GM + 决策人双桶，用户裁定 D-1）`；45 个文件、+14,424/−52 |
| 文档提交（B） | 见返回控制器的消息 | 本报告 + `recomputed.txt` 的一处更正（**纯文档，无生产/测试字节改动**） |

> 计划分两个提交，生产+测试+证据在 A、报告与更正痕迹在 B。`progress.md`（控制器的台账）**全程未 stage、未提交**（提交前 `git diff --cached --stat` 逐条扫过；`git status --porcelain` 确认它一直是 ` M`）。

**文件清单（A）**：改 5 个 `simos-app` 文件 = `tools/SimosToolSource.java`（+20 导入、GM 桶 +20 行、决策桶 +20 行、Javadoc 去数字）、`Shell.java`（7 处纯注释去数字，见 §6）、`test/…/SimosToolsTest.java`、`test/…/McpServerTest.java`、`test/…/McpPortTopologyTest.java`；新增 20 个 `tools/write/Unit*Tool.java`；证据 21 份。
**零领域改动**：`git diff --stat 40e19d2 -- AbstractNarrowWriteTool.java simos-unit simos-map simos-sd simos-core` **输出为空**（基类一个字未动，四个领域模块/核心未动）。

## 3. 一行测试小结

`./mvnw clean verify`（**第 1 次尝试**，前台起跑、未被杀、无作废轮）**rc=0**：8/8 模块 `SUCCESS`、**1436** 条 = `170/369/45/259/179/141/273`、`Failures 0 / Errors 0`、`[ERROR]` **0** 行、`BugInstance size is 0` **×7**、`COMPILATION ERROR` **0**、前端 `[frontend-gate] OK tests=204 pass=204 fail=0`、Maven `Total time 09:27 min`（前台墙钟 **574 s**）。
日志 `m2-evidence/logs/full-verify.log`（632,711 B、md5 `56a1c3f5913de391a5b9a5c737a5fde0`），逐模块与逐项计数由**两条独立口径现场重算**（模块汇总行求和 / 逐类 `-- in` 行求和），**同值 1436**，见 `m2-evidence/logs/recomputed.txt`。

**差量**（与简报 §8 给的 M1 参照对差）：六个非 app 模块**逐值相同**；`app 251 → 273 = +22`，由**独立第二口径**佐证——`SimosToolsTest` 的 `@Test` 计数 `git show 40e19d2:… | grep -c '^  @Test'` = **23** → 本树 **45** = **+22**（20 条坏载荷 + 1 条名字同源 + 1 条桶/工具同源）。★ 参照值本身**不是我实测的**（未在基线上重跑 verify），只作对差用，见 §7。

## 4. 判据 §5 逐条实测值

### 5.1 桶正确（EXTERNAL 12 / GM 40 / DECISION_AGENT 31 / EXTERNAL_WITH_GM 43）

| 桶 | 期望 | 实测 | 断言点（`SimosToolsTest`） |
|---|---|---|---|
| `EXTERNAL` | 12 | 12 | `:514-518` `.doesNotContain(通用写…)` + `.doesNotContainAnyElementsOf(MAP_WRITE_NAMES)` + `.doesNotContainAnyElementsOf(UNIT_WRITE_NAMES)` + `.hasSize(12)` |
| `GM` | 40 | 40 | `:499-503` `.doesNotContain("simos.command.submit")` + `.containsAll(MAP_WRITE_NAMES)` + `.containsAll(UNIT_WRITE_NAMES)` + `.hasSize(40)` |
| `DECISION_AGENT` | 31 | 31 | `:506-510` `.doesNotContain("simos.command.submit","sd.SetViewScope","sd.StartDecision")` + `.containsAll(UNIT_WRITE_NAMES)` + `.doesNotContainAnyElementsOf(MAP_WRITE_NAMES)` + `.hasSize(31)` |
| `EXTERNAL_WITH_GM` | 43 | 43 | `:527-531` `.containsAll(MAP_WRITE_NAMES)` + `.containsAll(UNIT_WRITE_NAMES)` + `.hasSize(43)` |

20 条 unit 工具**同时**出现在 GM 与决策人两桶，**不出现**在 EXTERNAL 桶——由 `.hasSize(12)` **与** `.doesNotContainAnyElementsOf(UNIT_WRITE_NAMES)` 两条**独立**钉住（只有规模会放过"换进来一条 unit 写"，只有黑名单会放过"规模巧合"）。
**真 SDK 侧的两条独立读数**（不经手抄常量）：`McpServerTest.initializeAndToolsListExposeExactlyTheExternalUnionGmTools:202-203`（`tools/list` == 43 条）；`McpPortTopologyTest.existingPortExposesExternalUnionGmToolFace:135-136`（43）、`decisionPortExposesOnlyDecisionAgentToolFace:152-153`（31）。
**运行期自报（与断言无关的第三个来源）**：干净轮日志里 Shell 装配自报 `tool=43` / `decisionTool=31`（各 167 次，`sort -u` 后唯一值）。

### 5.2 名字同源（`name()` == 固定命令类型；20 个类型都在 catalog）

- `SimosToolsTest.unitNarrowWriteToolsAreNamedAfterTheirFixedCommandType:436-437`：20 条工具 `name()` `.containsExactlyElementsOf(UNIT_WRITE_NAMES)`（**精确集合相等**，不是按名包含）。
- 同方法 `:439-443`：同一批名字在 **GM 桶**里按名可寻、在 **决策人桶**里也按名可寻（D-1）。
- 同方法 `:447-448`：20 个类型都在 catalog（名字能到达已注册 handler）。
- `registryContainsExactlyTheExternalUnionGmTools:364`：注册表 == 43 条常量（精确相等）。
- `catalogCoversEveryCommandHandlerImplementation:475-483`：扫源码得到的 43 个 `*Handler.java` 的 `type()` 集合 == catalog 集合（`:476` 先断言 `hasSize(43)`）。

### 5.3 敏感写（20 条逐条被断言覆盖）

`SimosToolsTest.writesAreSensitiveAndAskWithTheToolNameAsClassKey:661-688`：
- `:664-665` 覆盖集 `.containsExactlyInAnyOrderElementsOf(WRITE_TOOL_NAMES)`（**34 条写全集**，其中含 20 条 unit——实测量 `WRITE_TOOL_NAMES` 段内 `"unit.` 命中 **20**）；
- `:666-669` 读/写名单互斥 + `读 ∪ 写 == 43 条全名单`（"名单加项却没登记到任一侧 ⇒ 这里红"）；
- 循环 `:670-680` 对**每条**断言：`spec().sensitive()==true`、`spec().noExport()==false`、`gate(context) instanceof ToolGate.Ask`、`classKey()==name()`、`kind()==AskKind.SENSITIVE`。
  实测判据点：20 条 unit 工具**逐条**走这 5 项断言（写全集 34 ⊇ unit 20），不是抽样。

### 5.4 前置即错（20 条各一条"坏载荷 ⇒ 可读 REJECTED 且 head 不变"）

**形态**：20 条**各自一个 `@Test`**（`:855`–`:1012`，**不是**一个循环里断 20 次）；每条经 `callNarrowWrite`（真 `Shell`、真 store、真 MCP 工具面）→ 断言助手 `assertDomainRejectedAndHeadUnchanged:1038-1051`：
`:1043` `success()==false`、`:1044` `code()=="REJECTED"`、`:1046` `message.reason` **含下列关键片段**、`:1049` `head` **不变**（revisions 行数不变）。

| # | 工具 | 触发载荷 | 断言含的关键片段（**逐字**，取自 recon §2-B / 现场实测） |
|---|---|---|---|
| 1 | `UnitRenameTool` | `{"id":"nope","name":"新名"}` | `单位不存在` ★该条**单独写**（唯一不走 `UnitPayloads`） |
| 2 | `UnitCreateTool` | 已存在的 `id` | `单位 id 已存在` |
| 3 | `UnitReparentTool` | `parent:"nope"` | `父单位不存在` |
| 4 | `UnitSetStrengthTool` | `member:-1` | `member 必须 ≥ 0` |
| 5 | `UnitPlaceAtTool` | `id:"nope"` | `单位不存在` |
| 6 | `UnitPlanRouteTool` | 只给 1 个 waypoint | `waypoints 至少两个` |
| 7 | `UnitCancelRouteTool` | `id:"nope"` | `单位不存在` |
| 8 | `UnitDisbandTool` | 仍是链的 commander | `仍是链` |
| 9 | `UnitSetStatusTool` | `status:"NOT_A_STATUS"` | **`字段 status 不是合法状态`**（★ 见下） |
| 10 | `UnitAttachTool` | `parent` 落自己子树内 | `会成环` |
| 11 | `UnitDetachTool` | 已是根单位 | `已是根单位` |
| 12 | `UnitReparentSubtreeTool` | `parent` 落自己子树内 | `会成环` |
| 13 | `UnitSetFormationOffsetTool` | `id:"nope"` | `单位不存在` |
| 14 | `UnitSplitFormationTool` | `subUnitIds:[]` | `subUnitIds 不得为空` |
| 15 | `UnitMergeFormationTool` | 两个不同格单位 | `只有同格才能合体` |
| 16 | `UnitPlanSparseRouteTool` | 相邻段不可达 | `稀疏路线的段不可达` |
| 17 | `UnitSetRejoinTargetTool` | `target` 是自身 | `回归目标不得是自身` |
| 18 | `UnitCreateCommandChainTool` | 已存在的 `chainId` | `链 id 已存在` |
| 19 | `UnitUpdateCommandChainTool` | `chainId:"nope"` | `链不存在` |
| 20 | `UnitApplyCasualtiesTool` | 未知装备键 | `未知装备键` |

★ **第 9 行按裁决（2）办**：recon 表里**没有**这条文案 ⇒ **没有猜**，在用例里**当场量**出实际文本 `字段 status 不是合法状态`（来自 `UnitPayloads.requireStatus`，载荷层）后**写实测文案**，**未写 token 断言**。
★ 第 1 行按简报 §5.4 的要求**单独写**（`unit.RenameUnit` 是唯一不走 `UnitPayloads` 的命令），未套用其余 19 条的统一模板。
★ 关键片段**均为整句/近乎整句**（不是"只判 token"）——`单位不存在` / `会成环` 这类短串本身**就是域层原文的完整消息**，不是字段名；本任务未发现"载荷层与域层都会说同一串"的情形（本仓 T4/T10-b 那一族的坑），故不存在"判不出是哪一层拒的"的断言。

### 5.5 同源强判据（本任务新增的护栏）

`SimosToolsTest.everyNarrowWriteToolClassIsWiredIntoTheGmBucket:601-609`：
- `:604` **先断言扫描非空且恰为 31**：`.as("扫描必须恰为 31 个窄写工具类（扫到 0 个/漏文件是『扫描器静默』陷阱 ⇒ 空 == 空 恒真）").hasSize(31)`；
- `:607-609` `GM 桶 ∖ 读名单` `.containsExactlyInAnyOrderElementsOf(implemented)`（`implemented` = 扫 `tools/write/*Tool.java` 抽出的 `NAME` 常量集合）。
- 扫描器 `narrowWriteToolNamesFromSources:619-637`：`:631` 对**每个**继承了窄写基类却抽不到 `NAME` 的文件**当场 `assertThat(matcher.find()).isTrue()`**（不许静默跳过）。
- 实测扫描数 = **31**（4 sd + 7 map + 20 unit），与 GM 桶窄写数**逐值相等**。

## 5. 变异（5 个变异体 / 6 轮；5 KILLED / 0 SURVIVED，其中 m4 首轮作废并留档）

装置：`m2-evidence/mutants/mut-round.sh`（干净世界还原 + md5 比对、白名单推送、`COMPILATION ERROR` 强制为 0、surefire 报告新鲜度、**运行期自报**、日志自指）；生成器 `regen-mutants.sh`（从 pristine 派生、锚点命中数断言）。

| 变异体 | 内容 | 判定 | **红点（被保护的那行本身）** |
|---|---|---|---|
| **m1** | 把 `UnitApplyCasualtiesTool` 从 `addDecisionAgentWrites` 删掉（GM 桶保留） | **KILLED** `rc=1` `Failures=3` `Errors=0` | ① **`McpPortTopologyTest.decisionPortExposesOnlyDecisionAgentToolFace:153`**（决策口**正向精确匹配**，= 简报点名的期望红）② `SimosToolsTest.roleBucketsNeverCarryGenericWrite:508`（决策桶 `containsAll(UNIT_WRITE_NAMES)`）③ `SimosToolsTest.unitNarrowWriteToolsAreNamedAfterTheirFixedCommandType:443`。★ 简报警告的"**若本变异没能让决策口精确匹配红，必须立刻上报**"**没有触发**——它红了。 |
| **m2** | `UnitCancelRouteTool.commandType()` 返回另一个**已注册**类型 `unit.PlaceAt` | **KILLED（但形态与简报期望不同，已披露）** `rc=1` `Failures=0` `Errors=49` | 红在**注册表重名守卫**：49 条 `IllegalArgumentException: 工具名重复: unit.PlaceAt`，位置 `SimosToolsTest.startShell:337` / `McpServerTest.startShell:162`（`ToolRegistry.putChecked` ← `McpSourceBridge.sync` ← `Shell.start`）。**不是**名字同源判据。 |
| **m2b** | 同上但返回**未注册**的 `unit.CancelRouteX`（无重名）——我加的**隔离形态** | **KILLED** `rc=1` `Failures=5` `Errors=1` | **`SimosToolsTest.unitNarrowWriteToolsAreNamedAfterTheirFixedCommandType:437`** = 被保护的那行（`containsExactlyElementsOf(UNIT_WRITE_NAMES)`）。连带红：`:364`（注册表 == 43 条）、`:502`（GM 桶按名可寻）、`:609`（同源集合相等）、`:665`（写面覆盖集）、`:899`（该工具的坏载荷用例撞 `NoSuchElement`）。 |
| **m3** | 新增孤儿 `write/UnitFooTool.java`（`NAME="unit.NotARealCommand"`，**不接任何桶**） | **KILLED** `rc=1` `Failures=1` | **`SimosToolsTest.everyNarrowWriteToolClassIsWiredIntoTheGmBucket:604`** —— `.hasSize(31)` 实测 `Expected size: 31 but was: 32`（多出 `unit.NotARealCommand`）。★ 这是新护栏的靶子，证明它不是装饰。 |
| **m4**（首轮，**作废**） | 决策桶 + 一条 `MapSetTerrainTool` **且**同步 `DECISION_AGENT_WRITES` | **判 VOID（空操作变异体）**，`m4.log` **留档不删** | 唯一红点是**正向精确匹配**（mutant `:154` ↔ clean `:153`）——即"常量同步了、桶里没进"。**运行期自报 `decisionTool=31`** 证明**生产侧变异体根本没生效**：生成器按注释串切分时**漏了尾随换行**，插入行被并进上一行的 `//` 注释 ⇒ 变异体**合法、可编译、却不生效**。该轮**不能**证明 m4 要证的命题。 |
| **m4-rerun**（修正后重跑） | 同上，生成器改按 `注释行 + "\n"` 切分 + 断言"插入行在行首" + 装置新增 ②b **语义落点探针** | **KILLED** `rc=1` `Failures=2` `Errors=0` | ① **`McpPortTopologyTest.decisionPortExposesOnlyDecisionAgentToolFace:165`**（mutant 行号；= clean `:164` 的 **`C7 反向③`** `.doesNotContainAnyElementsOf(MAP_WRITES)`）② **`SimosToolsTest.roleBucketsNeverCarryGenericWrite:509`**（决策桶 `.doesNotContainAnyElementsOf(MAP_WRITE_NAMES)`）。★ **正向精确匹配绿**（失败报告里 `'C7：决策人口'` 文本命中 **0**；`'C7 反向③'` 命中 **1**）——**两侧同改**下只剩加注过的负向断言守得住，**裁决 J 的"不是装饰"由此变为可证伪并已证**。★ 运行期自报 **`decisionTool=32`**（生产侧变异真的生效，独立于断言）。 |

**每轮九道自证**（各轮日志的「装置自记」段，5 轮齐全）：`compilation_error_count=0`、`reports_fresh=1`、`post_restore_ok=1`、`pushed_*=…`（本轮推的字节 md5）、`rc`、`verdict`。
**m4 的两处装置缺陷已修进装置本身**（`regen-mutants.sh` 的注释 + `mut-round.sh` 的 ②b/⑤a）——空操作变异体不会再被报成 KILLED。

## 6. 控制器补丁（裁决 L）——逐处 before / after

`Shell.java`（**7 处纯注释**，零行为；`git diff 40e19d2 -- …/Shell.java` 只有这 7 段）：

| # | 位置 | before（逐字） | after（逐字） |
|---|---|---|---|
| 1 | `:131-133` 类 Javadoc | `现有口 {@code EXTERNAL_WITH_GM} 16 工具 = 3 通用写 + 4 GM 窄写 + 9 读；决策人口 {@code DECISION_AGENT} 11 工具 = 2 窄写 + 9 读` | `现有口 {@code EXTERNAL_WITH_GM} = 通用写 + GM 窄写 + 读工具；决策人口 {@code DECISION_AGENT} = 自有窄写 + 读工具。**条数以工具面为准**，不在此钉死` |
| 2 | `:164` 字段 Javadoc | `{@link …#EXTERNAL_WITH_GM} 的 16 条工具经桥同步进此表` | `{@link …#EXTERNAL_WITH_GM} 的全部工具经桥同步进此表` |
| 3 | `:173` 字段 Javadoc | `{@link …#DECISION_AGENT} 的 11 条工具经桥同步` | `{@link …#DECISION_AGENT} 的全部工具经桥同步` |
| 4 | `:435` 行内注释 | `= 9 读 + 3 通用写 + 4 GM 窄写。` | `= 9 读 + 通用写 + GM 窄写（条数以工具面为准）。` |
| 5 | `:453` 行内注释 | `仅 DECISION_AGENT 桶（9 读 + 2 窄写）` | `仅 DECISION_AGENT 桶（9 读 + 该桶自有窄写）` |
| 6 | `:644-645` Javadoc ★ | `{@code GUEST} 桶下 12 条工具里的 3 条写工具全部不可达` / `{@code DEFAULT} 是**满足全部 12 条工具的最小桶**` | `{@code GUEST} 桶下三条通用写工具（{@code command.submit}/{@code advance}/{@code fork}）全部不可达` / `{@code DEFAULT} 是**满足该工具面全部工具的最小桶**` |
| 7 | `:691` 方法 Javadoc | `现有口 16 条工具（3 通用写 + 4 GM 窄写 + 9 读）的活清单` | `现有口全部工具（通用写 + GM 窄写 + 读）的活清单` |

★ **诚实说明（第 6 处的 `12 条工具`，即裁决 L 要我报的那条）：这个 `12` 在我动手之前就已经是陈旧的**——基线 `40e19d2` 的 `Shell.java` 里它写作 `12 条工具`，而当时现有口已是 **23 条**（M1 之后）；它不是我引入的，也不是本次改动让它在今天才过期。我按裁决 L 把两处 `12` 一并去数字（改成按名罗列三条通用写 + "该工具面全部工具"），**去数字的形态与其余 6 处一致**。

`SimosToolSource.java`（同批，纯注释）：

| # | 位置 | before（逐字） | after（逐字） |
|---|---|---|---|
| 8 | 类 Javadoc（GM 桶说明） | `+ **7 条 map 窄写**（M1）。` | `+ **map 域与 unit 域的窄写**。` |
| 9 | `addDecisionAgentWrites` Javadoc `:194-196` | `/** 决策 Agent 窄写（N9）：**无** `sd.SetViewScope`、**无**通用写。 */` | `决策 Agent 窄写（N9）：**无** `sd.SetViewScope`、**无**通用写。★ 用户裁定 D-1：unit 域 20 条窄写与 GM 桶**同批**（它们是窄工具、命令类型固定，与 N9「不给通用写」不冲突）⇒ 两处**各自逐条**列出，便于按桶裁剪。` |
| 10 | `Role.EXTERNAL_WITH_GM`/桶说明段 | `**7 条 map 目标进的是 GM 桶**…{@link Role#EXTERNAL_WITH_GM} 复合口因此 也含这 7 条。`（★ 原串里 `因此` 与 `也含` 之间**有一个多余空格**） | `**窄写工具进的是 GM 桶**…它们的命令类型在工具里固定死，模型只能给载荷 ——与 {@code simos.command.submit} 的"自选 type"相对（spec §八.3）。{@link Role#EXTERNAL_WITH_GM} 复合口因此也含这些。`（★ 多余空格已删） |

★ **按裁决 L 明确不动的两处**（已核，逐字仍在）：`SimosToolSource.java:56` `**9 读 + 各桶自己的写面**`、`:99` `外部 MCP 桶（与既有行为逐条相同：3 写 + 9 读）`。
★ 本补丁**不改变任何判据、不改变任何桶规模、不改变变异清单**——7 + 3 处全在注释/Javadoc 内，`simos-app` 的生产字节**只有注释变化**（编译产物类名、注册顺序、工具数均不变；干净轮运行期自报 `tool=43 decisionTool=31` 与变异前的读数一致）。

## 7. 我未能核实的

1. **简报 §8 给的 M1 参照 `170/369/45/259/179/141/251` 我没有自己在基线 `40e19d2` 上重跑 verify**（`clean verify` ~574 s，一次只准一个重活）⇒ 上表"六个非 app 模块逐值相同"是**与简报给的数字对差**，不是与我自己跑出来的基线对差。app 的 `251→273` 有**独立第二口径**佐证（`@Test` 计数 23→45），其余模块的"相同"**只有单一口径**。
2. **`modes.js` 的 GUI 写白名单行为我未重测**——简报 §6(e) 的"这 20 条是 MCP/agent 面、工作台看不到也发不出"**是我照抄的既有口径（T10-l），不是我实测的**；本任务**未改任何 JS**，也没有任何用例覆盖这一条。
3. **决策人桶的逐条 `spec()/gate()` 我只在"现有人口 = GM 桶"这份注册表上逐条断言过**（`writesAreSensitiveAndAskWithTheToolNameAsClassKey` 遍历的是 `EXTERNAL_WITH_GM` 的工具面）。决策人口**逐工具**的敏感位/`AskKind` 没有单独循环断言——它依赖"两个桶引用的是**同一批类**（`containsExactlyElementsOf` 的名字同源 + 同一份 20 个类）"这一**推导**，不是直接读数。**证据级不等于实测级**，故列此。
4. **变异轮跑在全量门禁之前**（05:55–06:04 变异轮，06:14 全量 `clean verify`）。变异轮之后我没有再动任何生产/测试字节——`SimosToolSource.java` md5 `ca8b0c7489c9aae4bbf7876464c3e6c2`、`SimosToolsTest.java` `5ed77cba2a5f951151154a5b6de7f016`、`McpPortTopologyTest.java` `137e1cb5e45188d5fb79efea39401a02` 在变异轮前（`pristine/orig_md5.txt`）与全量门禁后**逐字相同**（装置每轮 ⑥ 段还原并自比）。★ 但"**变异轮的红是在最终字节上产生的**"这一点我是靠 **md5 相等**推出来的（装置做了逐轮还原 + 自比），**没有**在最终字节上重跑一遍变异轮。
5. **`recomputed.txt` 的一处**自纠**：该文件原先记的源日志 `size = 455,084 B` 是**错的**（现场 `stat -c %s` = **632,711 B**；同一文件里记的 md5 `56a1c3f5…` 与现状**相符**，行数/计数器/两条求和口径也全部相符）。错的来源**我没有查清**（未复核当时的中间读数或手误）⇒ 我在本次文档提交里按实测更正，并保留更正痕迹。**"两次相符"不等于"每个字段都验过"**——这次是我自己的取证产物中招。
6. **`m2` 的"简报字面形态不可达"是实测结论，不是推断**：20 个 unit 类型**全部**已有同名窄工具 ⇒ 任何"改成另一个**已注册** unit 类型"都会撞注册表重名守卫，红点因此落在 `Shell.start` 的构造期（49 条 error），**不是** §7 期望的"名字同源判据红"。我**没有**为此放松任何断言，而是加了**隔离形态 m2b**（未注册名）把该判据**单独**钉住（红在 `:437`）。
7. **简报 §6(a)/(b) 的前提与本树基线不符（第二处前提分歧）**：§6(a) 说 `EXTERNAL_UNION_GM_TOOL_NAMES` "**是 `union(READ, WRITE)` 的派生式常量就保持派生式**"，而基线（`git show 40e19d2:…`）里它与 `WRITE_TOOL_NAMES` **都是手抄的 `List.of(...)`**；§6(b) 自己也把 `McpServerTest` 那份称作"手抄的第二份"。⇒ 我的处置：**保持原形态**（两份手抄常量各自扩到 43），**没有**改写成派生式（那会改掉基线形态、且 §6(a) 的"别改成手写 43 条"恰恰预设了它是派生的），也**没有**新增第三份名单。
8. **未覆盖/未测**：真档（19441 格）上的工具面未跑（本任务的 e2e 全在合成小世界 `--demo`）；20 条工具**审批链路**的逐条走通未测（本任务只验"坏载荷 ⇒ REJECTED"，正向审批链由 M1/M5 既有用例覆盖）；`--bind-address`、多视口、前端**均未涉及**（本任务零 JS 改动）。
9. **`mcpCaller()` 的 `12 条工具`**（裁决 L 第 3 项）已在 §6 第 6 处据实说明：**它早在我开工之前就是陈旧的**。

## 8. 范围声明（照抄 t10-l 口径）

这 20 条窄写工具是 **MCP / agent 面**：**工作台（GUI）看不到也发不出**（`webui/modes.js` 的 unit 白名单只有 6 条且 fail-closed，本任务**未改任何 JS**）。⇒ **"全绿"不等于"界面上能用"**。
