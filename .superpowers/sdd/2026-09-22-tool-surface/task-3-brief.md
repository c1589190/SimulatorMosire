# Task 3 简报 — M3 sd 组（12 条窄写工具，进 GM 桶）

> 控制器交给你的**唯一需求来源**。下面的文件名/类名/字符串/行号**逐字照用**。
> 行号会漂移：**先按名找，行号只当提示**。
> ★ 你的**事实附件**是 `.superpowers/sdd/2026-09-22-tool-surface/m3-recon.md`（只读取证：16 条 sd 命令权威清单、
> 载荷真值表、逐字拒绝文案、静默缺口表、桶接线点、爆炸半径都在那里）。**本简报是需求，那份是事实**——
> 冲突时以**本简报**为准并当场报分歧。

## 0. 你在哪 / 干什么

- 工作区：**worktree `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1`**（分支 `ts/m1`）。★ **本仓同时有主检出与 worktree，同名文件各一份 ⇒ 改文件一律用 worktree 内的绝对路径。**
- 项目：SimulatorMosire（simos），模块化仿真引擎。
- **你要做的**：sd 域有 **16 条写命令**，其中 **4 条已有窄写工具**（`sd.IssueDirective` / `sd.SubmitVerdict` /
  `sd.SetViewScope` / `sd.StartDecision`）⇒ **你要为剩下 12 条各包一个窄写工具**，**12 条都进 GM 桶**。
- 前置：**M1 已完成**（map 域 7 条）、**M2 已完成**（unit 域 20 条）。**照抄它们的形制，不要发明。**

### 0.1 ★ 本任务**不含** D-5（导入器 tag 改 `nation:`）

`D-5`（`tools/gsimap_import.py` 的 tag 产出改 `nation:<名>`）**已从本任务剥离**，控制器另行处置。
**你不要碰 `tools/` 下任何文件、不要碰 `simos-app/src/test/java/.../demo/RichWorldTest.java`。**
理由（侦察实测，供你理解边界）：本机**没有导入器输入侧真档**，改完**无法按用户要求复验**，
而它的期望值改动会牵动一张**无法在本机跑起来**的黄金校验脚本 ⇒ 归控制器裁定，不归你猜。

## 1. 形制 —— 照抄这些文件，不要发明

- 基类（**同包、package-private，你在同包内可直接继承，一个字都不要改**）：
  `simos-app/src/main/java/io/mosire/simos/app/tools/write/AbstractNarrowWriteTool.java`
- 模板：`simos-app/src/main/java/io/mosire/simos/app/tools/write/IssueDirectiveTool.java`（29 行，sd 域自己的）
- 基类已钉死：schema（`payloadJson`/`branch`/`expectedRevision`，required 只有后两者）、
  `spec()` = `ToolSpec.level(AccessToken.DEFAULT, true, false)`（`sensitive=true` ⇒ 走审批门链）、
  `gate()` = `ToolGate.Ask(name(), summary, AskKind.SENSITIVE)`、`resources()` = `ToolSupport.ALL_WRITE`、
  `execute()`（组信封 → `core.submit` → `ToolSupport.fold`）、`name()` = `commandType()`。

你**每个类只写**：构造器 + `commandType()` + `summary(args)` + `description()` + **中文类 Javadoc**。

## 2. 要建的 12 个类

包：`io.mosire.simos.app.tools.write`。类名与 `NAME` **下表逐字照用，不要自己推**：

| # | 类名 | `NAME` | `description()` 返回串（**逐字照用**） |
|---|---|---|---|
| 1 | `SdCreateNationTool` | `sd.CreateNation` | `"建国家：固定 sd.CreateNation，载荷 {nationId, name, homeRegionId, adminBudgetPerTick}（四者全必填；★ homeRegionId 指向的区域必须**已带 `nation:` 前缀的 tag**，否则被拒——R13）"` |
| 2 | `SdCreateArmyTool` | `sd.CreateArmy` | `"建军：固定 sd.CreateArmy，载荷 {armyId, nationId, rootUnitId, name}（四者全必填；★ nationId 与 rootUnitId 指向不存在者即被拒）"` |
| 3 | `SdCreateDecisionMakerTool` | `sd.CreateDecisionMaker` | `"建决策人：固定 sd.CreateDecisionMaker，载荷 {id, affiliation, allowedTools, cadence}（四者全必填；★ 创建期 viewScope 恒为空范围、本命令不接受该字段，传了会被静默忽略；★ allowedTools 不得含通用写）"` |
| 4 | `SdPutInfoTool` | `sd.PutInfo` | `"写 Info：固定 sd.PutInfo，载荷 {address, key, value, note?}（前三者必填；★ value 是裸值，只保证标量往返，结构化值读回不保证逐字段相等）"` |
| 5 | `SdCreateCombatTool` | `sd.CreateCombat` | `"建交战：固定 sd.CreateCombat，载荷 {combatId, name, participants?}（前两者必填；★ participants 缺省即空集，可建出零参与者交战）"` |
| 6 | `SdAddCombatStageTool` | `sd.AddCombatStage` | `"加战斗阶段：固定 sd.AddCombatStage，载荷 {combatId, stage, combatStateId?, hex?}（combatId/stage 必填；★★ combatStateId 与 hex 只在**该交战的首个阶段**生效——非首阶段时给了会被**静默忽略**，不报错）"` |
| 7 | `SdSetStageOutcomeTableTool` | `sd.SetStageOutcomeTable` | `"设阶段结局表：固定 sd.SetStageOutcomeTable，载荷 {combatId, stageId, outcomes}"` |
| 8 | `SdCommitCombatOutcomeTool` | `sd.CommitCombatOutcome` | `"定结局：固定 sd.CommitCombatOutcome，载荷 {combatId, stageId, selectedOutcomeId}（三者全必填；★ 结局必须在该阶段的 outcomeTable 里，且**已选定过就不再覆盖**）"` |
| 9 | `SdRecordCasualtiesTool` | `sd.RecordCasualties` | `"记战损：固定 sd.RecordCasualties，载荷 {combatId, stageId, deltas[{unit, lossClass, personnel?, equipment?}]}（deltas 必须非空；★ personnel 缺省 0、equipment 缺省空表 ⇒ 可写出零损失记录；★ 未知装备键会被拒，不视作 0）"` |
| 10 | `SdRegisterEffectTool` | `sd.RegisterEffect` | `"登记效果：固定 sd.RegisterEffect，载荷 {effectId, kind, trigger, action, createdTick?}（前四者必填；★ createdTick 缺省 = 当前 tick）"` |
| 11 | `SdCancelEffectTool` | `sd.CancelEffect` | `"取消效果：固定 sd.CancelEffect，载荷 {effectId}（★ 只有可取消状态的效果才允许）"` |
| 12 | `SdSetDecisionMakerProviderTool` | `sd.SetDecisionMakerProvider` | `"配 provider：固定 sd.SetDecisionMakerProvider，载荷 {decisionMakerId, providerId}（两者全必填；★★ 本命令**只校验 providerId 非空白，不校验 provider 是否存在**——存在性在**使用时刻**解析，解析不到即 fail-closed，绝不静默兜底）"` |

`summary(args)` 一律照 M1/M2 的形态（把中文短语换成各工具自己的动作）：

```java
  @Override
  protected String summary(Map<String, Object> args) {
    return "建国家 branch=" + args.get("branch") + " expected=" + args.get("expectedRevision");
  }
```

★ **`description()` 里的 ★/★★ 警告是承重内容，不许简化掉**——它们是**给模型看的**。
特别是第 6 条（非首阶段静默忽略）与第 12 条（不校验 provider 存在性）：
这两条是**工具面上唯一的披露点**，少了模型会以为"写错了会被拒"，而实际上**命令照落 revision**。

★ 中文与 `{}` 混排会被 google-java-format 折行——**写完跑 `./mvnw -q spotless:apply`，不要手工调行宽**。

## 3. 接线：**一个**桶

文件 `simos-app/src/main/java/io/mosire/simos/app/tools/SimosToolSource.java`。

- **追加点**：`addGmWrites(...)` 方法体**末尾** → 追加这 12 条。
  ★ M2 之后该方法末尾是 20 条 unit 工具（以 `UnitApplyCasualtiesTool` 收尾）；
  M2 若尚未合并，则以 `MapRegisterPathwayGroupTool` 收尾——**按名找方法，按方法体末尾追加**。
  ★ **不要**插到既有工具前面（顺序无判据依赖，但保持"追加在末尾"这一贯做法）。
- **`addDecisionAgentWrites(...)` 一个字都不要动**：sd 这 12 条**不进决策人桶**（决策人只出令/判决）。
- **`addExternalWrites(...)` 一个字都不要动**。
- **`EXTERNAL_WITH_GM` 不用动**：它是 `addExternalWrites(...) + addGmWrites(...)` 的并集，**自动含**这 12 条。

★ **追加后四桶规模**（实测应为）：

| 桶 | M2 后 | **M3 后** | 算式 |
|---|---|---|---|
| `EXTERNAL` | 12 | **12**（不变） | 9 读 + 3 通用写 |
| `GM` | 40 | **52** | 9 读 + 11 窄写 + 20 unit + **12 sd** |
| `DECISION_AGENT` | 31 | **31**（不变） | 9 读 + 2 sd + 20 unit |
| `EXTERNAL_WITH_GM` | 43 | **55** | 9 读 + 3 通用写 + 43 窄写 |

（★ 上表是**期望值**，不是让你照着改断言去凑——**先接线，让断言自己红出来，再改常量**。）

## 4. 前置即错 —— 本任务**不加**工具层前置校验（与 M1/M2 同裁决，照办）

**裁决：12 条命令的域层已有可读拒绝文案 ⇒ 工具层不重复校验。** 理由与 M1/M2 逐条相同：

1. 域层拒绝经 `ToolSupport.fold` 变成 `ToolResult.error("REJECTED", <域层理由原文>)` ⇒ **可读理由已到达调用方**；
2. 工具层的校验**可被 `simos.command.submit` 绕过** ⇒ 那是装饰（「护栏必须自证」）；
3. ★ **技术上也做不到**：载荷解析器 `SdPayloads` 是 `simos-sd` 的**包级私有**，`simos-app` 的工具层**看不见它**。

⇒ **你的义务是"证明拒绝理由真的到达调用方"**（判据 §5.4），不是"再写一遍校验"。

★★ **一条本任务特有的硬禁令**：**不许给 `SdSetDecisionMakerProviderTool` 加 provider 存在性校验。**
**理由不是"某条既有用例会红"，而是 §4 的那条通则本身**（工具层校验**可被 `simos.command.submit` 绕过** ⇒
装饰；「护栏必须自证」）——这一条在这里**尤其**成立，因为 sd 的设计是**使用期解析、解析不到即 fail-closed**。

★★ **控制器复核更正（2026-09-22，你按更正后的读）**：
`simos-app/src/test/java/io/mosire/simos/app/SdProviderBindingEndToEndTest.java:84`
（`bindingAnUnregisteredProviderIsAllowedBecauseExistenceIsResolvedAtUseTime`）**确实**把
"未注册 provider 也被接受"钉成了判据——**但它钉的是「sd 域层」**：
该用例走 `core.submit(envelope(...))`（`SdProviderBindingEndToEndTest.java:87-92`）**直接提交信封**，
**根本不经过任何工具**（其 javadoc 原文：`若有人把存在性校验塞回 sd，本用例会红`——**"塞回 sd"**）。
⇒ **推论（本简报早先写错了，以此处为准）**：
① **域层**加校验 ⇒ 该用例**会红**（但它属 `simos-sd`，§10 已禁你改动）；
② **工具层**加校验 ⇒ 该用例**照样绿**（它绕过了工具）——**所以不能拿它当工具层禁令的依据**。
⇒ 禁令照旧（理由换成上面的通则），但**别再引用 `:84` 当"工具层加校验会红"的证据**。

**下面这些既有行为控制器已裁「不在 M3 修」，你也不要顺手修**（它们是**既有的**、今天经通用写就可达，
改它们会牵动已关账的变异轮——裁定 42：改动被测文件 ⇒ 旧证据作废、须重跑）：
`sd.AddCombatStage` 非首阶段静默忽略 `combatStateId`/`hex`（S1·**真缺口**）；`sd.CreateCombat` 的
`participants` 缺省空集（S2）；`stage` 子对象各字段静默取缺省（S3）；`sd.RecordCasualties` 的
`personnel`/`equipment` 静默取缺省（S4）；`sd.IssueDirective` 的空令被接受（S5）；`createdTick` 缺省
当前 tick（S6）；`viewScope` 子字段静默取缺省（S8）；`sd.CreateDecisionMaker` 传 `viewScope` 被静默忽略（S9）。
（逐条锚点见 recon §3.2。**你的处置 = 把它们写进对应工具的 `description()`**，不是改域层。）

## 5. 判据（必须逐条有实测值）

1. **桶正确**：12 条新工具**同时**出现在 `GM` 与 `EXTERNAL_WITH_GM` 里；
   **不出现**在 `EXTERNAL` 桶里、**也不出现**在 `DECISION_AGENT` 桶里。
   四桶规模实测应为 **EXTERNAL 12 / GM 52 / DECISION_AGENT 31 / EXTERNAL_WITH_GM 55**。
2. **名字同源**：每条工具的 `name()` == 其固定命令类型；12 个类型都已在 `catalog` 里。
3. **敏感写**：12 条 `spec().sensitive()` 为真、`gate()` 是 `AskKind.SENSITIVE` 的 `Ask`、`classKey == name()`。
4. **★ 前置即错（本任务的核心判据）**：12 条工具**各一条**"坏载荷 ⇒ 可读 `REJECTED` 且 head 不变"的用例。
   断言里**要含下表的**关键片段（「只判 token 判不出是哪一层拒的」是本仓反复踩过的坑）。
   **你可以自选更好的触发载荷，但文案必须来自本表/recon §3.3（当场跑出来的原文为准，不要猜）**：

   | 工具 | 建议坏载荷 | 关键片段 |
   |---|---|---|
   | `SdCreateNationTool` | `homeRegionId` 指向一个**不带 `nation:` 前缀 tag** 的区域 | `无国家 tag` |
   | `SdCreateArmyTool` | `nationId` 指向不存在的国家 | `nationId 不存在: ` |
   | `SdCreateDecisionMakerTool` | `allowedTools` 含 `simos.command.submit` | `不得含通用写` |
   | `SdPutInfoTool` | `address` 非法（`Address.parse` 抛） | 见 recon §3.3（**当场取证后写实测文案**） |
   | `SdCreateCombatTool` | `combatId` 已存在（先建一个） | `交战已存在: ` |
   | `SdAddCombatStageTool` | `combatId` 不存在 | `交战不存在: ` |
   | `SdSetStageOutcomeTableTool` | `stageId` 不存在（交战存在） | `阶段不存在: ` |
   | `SdCommitCombatOutcomeTool` | `selectedOutcomeId` 不在该阶段结局表里 | `结局不在该阶段的 outcomeTable 里` |
   | `SdRecordCasualtiesTool` | `equipment` 含未知装备键 | `未知装备键: ` |
   | `SdRegisterEffectTool` | `action` 引用的阶段不存在 | `action 引用的阶段不存在: ` |
   | `SdCancelEffectTool` | `effectId` 不存在 | `效果不存在: ` |
   | `SdSetDecisionMakerProviderTool` | `decisionMakerId` 不存在 | `决策人不存在: ` |

   ★★ **每条各一个用例，不许写成一个循环里断 12 次**（循环里断 N 次时，变异杀掉一条其余照样绿，判别力被稀释）。
   ★★ **每条都要先造出使该前置可达的状态**（例：`SdCreateCombatTool` 的"已存在"要求先成功建一次；
   `SdCommitCombatOutcomeTool` 要求先有交战国+阶段+结局表）。**不要用"跳过前置、直接期望别的错"来凑**
   ——那样断言会退化成判另一个前置。

5. **★ 同源强判据（M2 新建，你要让它跟着长大）**：
   扫 `tools/write/*Tool.java` 的 `NAME` 常量，断言 **"实现了窄写工具类的集合 == GM 桶的窄写工具集合"**。
   M2 落地后它已经在（形如 `SimosToolsTest` 里扫 `*Tool.java` 的那条）；
   **M3 加 12 条后它应自动覆盖**（若它写了 `hasSize(N)` 之类的具体数，**跟着更新**）。
   ★ 若它**先断言扫到的集合非空**，保留那句（否则扫描器写错时会退化成"空 == 空 恒真"——本仓踩过，见纪律）。
   ★ 若 M2 因故没落这条判据，**你补上**（形态照 `SimosToolsTest.catalogCoversEveryCommandHandlerImplementation`）。

## 6. 爆炸半径 —— 会红的 4 处 + 3 个常量，**全部**要你处置

★ 结论：**必红 4 处**，全部是"**精确集合相等**"型；**按名 `contains`/`doesNotContain` 型一律不红**。

**(a) `simos-app/src/test/java/io/mosire/simos/app/tools/SimosToolsTest.java`**
- `EXTERNAL_UNION_GM_TOOL_NAMES`（M2 后 **43**）⇒ **55**；`WRITE_TOOL_NAMES`（M2 后 **34**）⇒ **46**。
- ★ **第 1 处必红**：`roleBucketsNeverCarryGenericWrite` 的 `externalWithGm` 块里 `.hasSize(43)` ⇒ 改 **55**。
- ★ **`writeFaceCoveredByTheWriteGate()` 不要动**——M1 把它改成**从真工具面派生**，它会**自动跟上**（**退回切片**就杀了它，M1 已有变异体）。

**(b) `simos-app/src/test/java/io/mosire/simos/app/McpServerTest.java`**
- 同形的 `EXTERNAL_UNION_GM_TOOL_NAMES`（M2 后 **43**）⇒ **55**。
- ★ **第 2 处必红**：`initializeAndToolsListExposeExactlyTheExternalUnionGmTools`（真 SDK `listTools()` 对拍）。
- ★★ **这份是"手抄的第二份"**（与 (a) 逐条相同）⇒ **必须两处同改**。

**(c) `simos-app/src/test/java/io/mosire/simos/app/McpPortTopologyTest.java`**
- `GM_NARROW_WRITES`（M2 后 **31**）⇒ **43**（+12 条 sd）
- `DECISION_AGENT_WRITES`（M2 后 **22**）⇒ **不动**（sd 12 条不进决策桶）
- ★ **第 3 处必红**：`existingPortExposesExternalUnionGmToolFace` 的精确匹配（现拼 `READ + GENERIC + GM_NARROW`）。
- ★ **第 4 处必红**：`catalogCovers…` 那类精确匹配若存在 ⇒ 跟着。**决策口的正向精确匹配不会红**（走 `DECISION_AGENT_WRITES`）。
- ★ 反向三条（`doesNotContainAnyElementsOf(GENERIC_WRITES)` / `doesNotContain("sd.SetViewScope")` /
  `doesNotContainAnyElementsOf(MAP_WRITES)`）**不会红**——但**跑一遍确认**，不要假定。
- ★ **顺带把 `.as()` 文案里的旧数字改对**（M2 后为 `9 读 + 22 窄写`；M3 后仍是 `9 读 + 22 窄写`，**决策口不变**）。
- ★ M2 给那三条负向断言加的 `.as(...)` 注**保持原样**，别动。

**(d) `simos-app/src/main/java` 的禁字约束**（不红，但新代码要遵守）
`AppWritePathGuardTest` 扫 `simos-app/src/main/java` 下全部 `.java`（**剥注释后**，但**字符串字面量不剥**）
禁止出现 `"SqliteStore"` / `"Timeline"` / `"CheckpointStore"`。
★ **保守做法：这三个词（含 Javadoc 里）干脆一个都不写**——剥注释器本身有被坑的历史。

**(e) 明确【不改】的四处**（recon 已证，别白干）
- `CatalogTool.PAYLOAD_HINTS`：sd **16 条全在**（`CatalogTool.java:65-101`，构造期强制）⇒ **零改动**。
  ★ 该规矩的对象是**命令 type**，M3 **不新增命令类型**（只加**工具**）。
  ★ 唯一例外见下面 (f)。
- `McpCoverageTest` 的 `EXPECTED_COMMAND_TYPES` / `MINIMAL_PAYLOADS`：sd **16 条全在** ⇒ **零改动**。
- `Shell.java`：sd 16 条 handler **已全部注册** ⇒ **handler 零改动**（M3 只加工具、不加 handler）。
  ★ **M2 已被控制器要求把该类里全部钉死工具条数的注释改成"不钉数字"的表述**（裁决 I + **补丁 L**，
  覆盖 `16 工具 = 3 通用写 + 4 GM 窄写 + 9 读` 那一族、`决策人口…的 11 条工具`、`仅 DECISION_AGENT 桶（9 读 + 2 窄写）`
  三处，以及 `SimosToolSource` 自己的 javadoc）。
  ⇒ **你的义务是"复核 + 不重新钉死"**：① 若你仍看到钉死的条数（按**串**找 `条工具` / `9 读` / `窄写`），
  按同法**去掉数字**；② ★ **你往 `addGmWrites` 追加 12 条时，不要在该方法 javadoc 里写新的条数**
  （写"sensitive 窄写工具一律在此追加"这类**不随数量漂移**的表述）。
  ★ **M2 若因故没做**（例如它被你接手时还没合并），上面的去数字由**你**补——它比你的 12 个类便宜得多。
- `webui/**`：**不改 JS**。★ 范围声明照抄进报告：这 12 条是 **MCP/agent 面**，
  **工作台（GUI）看不到也发不出** ⇒ **"全绿"不等于"界面上能用"**（T10-l 口径）。
- `simos-sd/**`：**一个字节都不要动**。★ 实证：`simos-sd` 的 22 个测试文件里
  `ToolSource`/`Role.GM`/`listTools` **零命中** ⇒ M3 是 `simos-app` 侧的纯增量。

**(f) ★ 唯一允许的 `description` 外改动（可选，加注更准）**
`CatalogTool.java:71-74` 的 `sd.AddCombatStage` 提示里写了 `combatStateId?(首阶段必填), hex{q,r}?(首阶段必填)`
——**用了 `?` 但没写"非首阶段时给了会被忽略"**。若你要补这句，**只改提示字符串的字面量**，
**不许动 `PAYLOAD_HINTS` 的结构与断言**。★ 若你判断不改也行，**如实写进报告**即可（非必做）。

## 7. 变异（≥3 条，每条都要**真的被杀**，且存活要如实报）

- **m1 GM 桶漏一条**：把 12 条里任意一条**从 `addGmWrites` 删掉** ⇒
  期望 **`McpPortTopologyTest` 的现有口正向精确匹配红**（GM 窄写集合少一条）。
- **m2 名字错**：把任意一条工具的 `commandType()` 返回值改成**另一个已注册的 sd 类型** ⇒
  期望**名字同源判据红**（`name()` 是 `commandType()` 派生的，所以这同时证了"名字不是独立字段"）。
- **m3 ★ 孤儿工具**：新增一个 `write/SdFooTool.java`（`NAME = "sd.NotARealCommand"` 或任一流派**合法但不接桶**），
  **不接进任何桶** ⇒ 期望**§5.5 那条同源判据红**。★ **这条证明新护栏不是装饰。**
  （变异体跑完**必须删掉该文件**并恢复干净世界。）
- ★ **可选 m4（若时间允许）——★ 期望已更正（控制器 2026-09-22），按更正后的读**：
  给 `SdSetDecisionMakerProviderTool` **加上** provider 存在性校验。**期望分两半，两半都要报**：
  - **① 既有判据不动**：`SdProviderBindingEndToEndTest` **照旧全绿**——因为它走 `core.submit(envelope(...))`、
    **绕过工具**。★ **这一半本身就是"工具层校验是装饰"的直接证据**（与 §4 的通则同口径）。
    ⇒ **不许**把它报成"变异体存活 = 护栏失效"；它是**设计上就该存活**的一轮。
  - **② 绕过演示（这才是本变异的要点）**：用**通用写** `simos.command.submit` 提交**同一个**
    `sd.SetDecisionMakerProvider` 信封（`providerId` 给一个从未注册的 id），观察它**照样 Committed**
    ⇒ 工具层那道校验**对任何持有通用写的人完全无效**。
  ★ 若 ① **意外变红**（即既有判据真的被工具层影响）⇒ **立刻如实上报**，**不要**顺手把它当成功劳：
    那意味着工具与 `core.submit` 之间存在我不知道的耦合，**比杀掉一个变异体重要得多**。
  ★ **不许**为凑一个红点放松任何断言。

变异纪律（本仓硬要求）：**按变异文件名（而非目标类名）拷入会让"红"变成编译错误 ⇒ 不算数**；
必须按**白名单**把变异体推成**目标类名**，并**强制断言日志里 `COMPILATION ERROR` 计数为 0**，
不为 0 就当场作废该轮。每轮开跑前把工作目录恢复成干净世界（重编原件、比 md5）。
**红的理由必须是被保护的那行本身**；**"没跑到" ≠ "没红"**（反应堆会在前面的模块红掉时短路，如实记）。

## 8. 门禁

- `./mvnw clean verify` **rc=0**；模块判据 **`SUCCESS [`** 8/8；`[ERROR]` **0** 行；
  `BugInstance size is 0` **×7**；前端 `[frontend-gate] OK … fail=0`。
- ★ **基线数字一律现场重算**（只取模块汇总行 `Tests run:` 相加），**不得引用任何文档里的现成数字**（含台账、含本简报）。
- ★ **本机 `nproc=2`**：一次只准有一个重活。**跑 Maven 时不要同时跑别的重活。**
- ★ **全量 `clean verify` 耗时压在 600 s 线附近**：前台起跑、给足超时；
  **"被杀"既不是红也不是绿**，如实记"第几次尝试"并留档不删。
- 迭代期只跑相关模块：`./mvnw -pl simos-app -am -Dtest=SimosToolsTest -Dsurefire.failIfNoSpecifiedTests=false test`。
- ★ 关账前**必须**跑过一次全量 `clean verify`（`mvn test` 不跑 SpotBugs）。

## 9. 证据 + 报告

- 证据目录：`.superpowers/sdd/2026-09-22-tool-surface/m3-evidence/`（**自己建**）。
  门禁日志、变异体与每轮日志、判据实测输出**都落这里**；★ **每份产物要自指**（把"这一轮跑的是哪份字节（md5）"追加进日志本身）。
- 报告写到 `.superpowers/sdd/2026-09-22-tool-surface/task-3-report.md`，含：
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
- ★ **不许改 `simos-sd` / `simos-unit` / `simos-map` / `simos-core`**（本任务**零领域改动**，只动 `simos-app`）。
- ★ **不许改 `tools/**`**（D-5 已剥离，见 §0.1）。
- ★ 注释与文档用**中文**，与既有风格一致。中文 Javadoc 的折行交给 `./mvnw -q spotless:apply`，**不要手工调行宽**。
- **你是实现者，不要派子代理**（不派助手，更不派评审）。评审在控制器收到你的报告之后。
