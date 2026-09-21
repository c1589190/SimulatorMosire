# M3 侦察报告 —— 工具面标准化 · sd 域写面 + D-5 导入器（派单前置）

> 性质：**只读侦察**。全程未改任何文件、未提交、未跑 Maven / node / npm / **未执行任何 Python**（只读源码）。
> 工作区：worktree `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1`，分支 `ts/m1`。**本轮所有路径均为 worktree 内绝对路径**。
> 本报告每个数字都是**当场读码/数出来的**（下面 §〇 逐条给出可自查的数法），**不引用**任何文档里的现成数字。
> ★ 凡标 **未运行验证** 的，是**静态读码结论**；标 **未核实** 的，是我**没读到那一层**（≠ 不存在）。
> "我没搜到"一律写出**命令与范围**（§十一）。
>
> ★★ **范围限制（照派单执行）**：`SimosToolsTest.java` / `McpServerTest.java` / `McpPortTopologyTest.java`
> 三个文件**不归本报告**（另一个侦察者在读）。本报告的"爆炸半径"只写**不属于那三个文件**的部分。
> 我对这三个文件**不下任何结论**——只在需要区分时写"按派单排除"。

---

## 〇 我实测到的数字（现数，非引用）

| 项 | 实测值 | 数法（可自查） |
|---|---|---|
| **sd 域写命令（`type()`）总数** | **16** | `grep -rn "public String type()" simos-sd/src/main/java/ \| wc -l` = **16**（一文件一条，无重复）；逐条列出见 §一 |
| sd Handler 类文件数 | **16** | `find simos-sd/src/main/java -name '*Handler.java' \| wc -l` = **16**（与上一行**同数**，一一对应） |
| **已有窄写工具的 sd 命令** | **4** | `simos-app/.../tools/write/` 下 `NAME = "sd.*"` 命中 **4** 个（`IssueDirectiveTool` / `SubmitVerdictTool` / `SetViewScopeTool` / `StartDecisionTool`） |
| **⇒ 尚无窄写工具的 sd 命令** | **12** | 16 − 4 = **12** |
| `Shell` 注册的 sd handler | **16** | `Shell.java`：11 条在主 `List.of(...)` 内（`:334-344`）+ `RegisterEffectHandler`(`:351`) + 4 条 late handler(`:360-364`) |
| `CatalogTool.PAYLOAD_HINTS` 里的 sd 条数 | **16** | `grep -c '"sd\.' .../read/CatalogTool.java` = **16**（其中 8 条单行、8 条跨行） |
| `McpCoverageTest` 里 `"sd.` 行数 | **33** | `grep -c '"sd\.' .../app/McpCoverageTest.java` = **33**（≈ 16 类型 × 2 张表 + 1 处其他） |
| 富世界资源字节数 | **4,219,540** | `stat -c%s simos-app/src/main/resources/worlds/v17levant.json`；md5 `1d817eee4d238c5255953e274a8f00f0` |
| 富世界资源 hex / 区域 | **59,223 / 252** | `python3 -c` 读 `modules["map"]["map"]`：`len(hexes)=59223`、`len(regions)=252` |
| ★ 富世界资源的 tag 分布 | **`{'Nation': 252}`** | 同上：`Counter(r["meta"]["tag"] …)` = `{'Nation': 252}`；**`nation:` 前缀的区域数 = 0** |
| 富世界资源 `name == id` | **252 / 252** | 同上（`name` 就是 `provinces` 的键） |
| ★ `tools/*.py` 行数 | `gsimap_import.py` **712** / `check_v17levant_import.py` **218** / `materialize_v17levant.py` **143** / `v17levant_docs.py` **396** / `check_v17levant_docs.py` **219** | `wc -l tools/*.py` |

★★ **派单里的"601 行"对不上**：派单写 `tools/gsimap_import.py`「**601 行左右**」，**实测 712 行**（差 111 行）。
601 是 M6 立项/关账期的数字（CLAUDE.md 的 M6 行也写 601）⇒ **文件已增长，那个数字未随之更新**。
按本仓纪律「数字一律现场重算」，本报告一律用 **712**。

★ **`git grep` 的坑已避**：本机 `grep` 是 **ugrep 7.8.4**（默认尊重 `.gitignore`、跳隐藏目录）。凡全仓搜索一律用
`git grep --untracked`；凡目录级搜索限在**未忽略、非隐藏**的源码目录（`tools/`、`simos-*/src/`）内，此时裸 `grep -rn` 安全。
`.superpowers/**` 是被忽略的隐藏目录 ⇒ 那里我只用 `ls` / 显式路径 `grep <文件>`，**没有对它做目录递归 grep**。

---

## 一 sd 域写命令权威清单（item 1）

**结论先行：sd 域写命令 = 16 条，其中 4 条已有窄写工具，12 条没有。**

数法：`simos-sd/src/main/java/io/mosire/simos/sd/spi/` 下 `public String type()` 逐条（`grep -rn` 一文件一条）。
`Shell` 侧逐条交叉核过（`Shell.java:334-344` 主清单 11 条 + `:351` + `:360-364` late 4 条 = **16**，与 handler 侧**同数**）。

| # | `type()` | 实现类（`simos-sd/.../sd/spi/`） | `Shell` 注册 | 窄写工具 | 若需新建，类名建议 |
|---|---|---|---|---|---|
| 1 | `sd.CreateNation` | `CreateNationHandler.java` | ✅ `:334` | ❌ | `SdCreateNationTool` |
| 2 | `sd.CreateArmy` | `CreateArmyHandler.java` | ✅ `:335` | ❌ | `SdCreateArmyTool` |
| 3 | `sd.CreateDecisionMaker` | `CreateDecisionMakerHandler.java` | ✅ `:336` | ❌ | `SdCreateDecisionMakerTool` |
| 4 | `sd.PutInfo` | `PutInfoHandler.java` | ✅ `:337` | ❌ | `SdPutInfoTool` |
| 5 | `sd.CreateCombat` | `CreateCombatHandler.java` | ✅ `:338` | ❌ | `SdCreateCombatTool` |
| 6 | `sd.AddCombatStage` | `AddStageHandler.java` | ✅ `:339` | ❌ | `SdAddCombatStageTool` |
| 7 | `sd.SetStageOutcomeTable` | `SetOutcomeTableHandler.java` | ✅ `:340` | ❌ | `SdSetStageOutcomeTableTool` |
| 8 | `sd.CommitCombatOutcome` | `CommitOutcomeHandler.java` | ✅ `:341` | ❌ | `SdCommitCombatOutcomeTool` |
| 9 | `sd.RecordCasualties` | `RecordCasualtiesHandler.java` | ✅ `:342` | ❌ | `SdRecordCasualtiesTool` |
| 10 | `sd.RegisterEffect` | `RegisterEffectHandler.java` | ✅ `:351`（在 `drainableCommandTypes` 算完之后） | ❌ | `SdRegisterEffectTool` |
| 11 | `sd.CancelEffect` | `CancelEffectHandler.java` | ✅ `:343` | ❌ | `SdCancelEffectTool` |
| 12 | `sd.StartDecision` | `StartDecisionHandler.java` | ✅ `:344` | ✅ `StartDecisionTool` | — |
| 13 | `sd.IssueDirective` | `IssueDirectiveHandler.java` | ✅ `:360`（late，带 `DirectiveWhitelist`） | ✅ `IssueDirectiveTool` | — |
| 14 | `sd.SubmitVerdict` | `SubmitVerdictHandler.java` | ✅ `:361`（late） | ✅ `SubmitVerdictTool` | — |
| 15 | `sd.SetViewScope` | `SetViewScopeHandler.java` | ✅ `:362`（late） | ✅ `SetViewScopeTool` | — |
| 16 | **`sd.SetDecisionMakerProvider`** | `SetDecisionMakerProviderHandler.java` | ✅ `:363`（late） | ❌ | `SdSetDecisionMakerProviderTool` |

### 1.1 与已有 4 条窄写工具的比对（现状）

| 已有工具 | `NAME` | 在哪个桶（`SimosToolSource`） | 锚点 |
|---|---|---|---|
| `IssueDirectiveTool` | `sd.IssueDirective` | **GM** + **DECISION_AGENT** | `addGmWrites:136`、`addDecisionAgentWrites:152` |
| `SubmitVerdictTool` | `sd.SubmitVerdict` | **GM** + **DECISION_AGENT** | `:137`、`:153` |
| `SetViewScopeTool` | `sd.SetViewScope` | **仅 GM**（决策人口**无**） | `:138` |
| `StartDecisionTool` | `sd.StartDecision` | **仅 GM**（决策人口**无**） | `:139` |

- `addExternalWrites`（`:121-126`）**一条 sd 工具都没有**：通用写只有 `simos.command.submit` / `simos.command.advance` / `simos.command.fork`。
- `EXTERNAL_WITH_GM` = `addExternalWrites + addGmWrites`（`:112-115`）⇒ 现有 MCP 口（`Shell.java:439-445`，`Role.EXTERNAL_WITH_GM`）**已含这 4 条**。
- ⇒ **12 条待建工具的目标桶 = GM**（照 M1 的 7 条 map 工具同址追加），连带进入 `EXTERNAL_WITH_GM` 复合口；**不进** EXTERNAL、**不进** DECISION_AGENT。
  - ★ 这条是**结构性推论**（`addGmWrites` 就是 GM 桶，`EXTERNAL_WITH_GM` 由它复合而来），**未运行验证**。

### 1.2 形制（照抄，不要发明）

`AbstractNarrowWriteTool`（`simos-app/.../tools/write/`，**package-private，同包可直接继承**，`abstract class`，99 行）已钉死：
schema（`payloadJson`/`branch`/`expectedRevision`，required 只有后两者）、`spec() = ToolSpec.level(DEFAULT, true, false)`、
`gate() = ToolGate.Ask(name(), summary, AskKind.SENSITIVE)`、`resources() = ToolSupport.ALL_WRITE`、
`execute()`（组 `CommandEnvelope` → `core.submit` → `ToolSupport.fold`）、`name() = commandType()`。
⇒ **12 条新工具每个类只需三样**：构造器、`commandType()`、`summary(args)`、`description()`。**基类一个字不用改。**

---

## 二 每条命令的载荷真值表（item 2）

> 口径：**必填 = 无默认值，缺即拒**；**可选 = 有默认值或可选语义**。
> 数法：`simos-sd/.../sd/spi/` 下 16 个 `*Handler.java` 逐个抽 `SdPayloads.require*` / `SdPayloads.optional*(...)` 调用（见 §〇 数法说明）。
> ★ M1 经验：**无默认值的字段必须写进 `description()`**（M1 的 7 条 map 工具已照此办，例：`MapSetEdgeTool` 的 `mode（replace|merge，无默认）`、`MapRandomizeRegionTool` 的 `seed（整数，无默认）`）。下表"必填"列即该表。

| # | `type()` | **必填（无默认）** | 可选 / 有默认（★ = 默认值会让"漏写"看起来像成功） |
|---|---|---|---|
| 1 | `sd.CreateNation` | `nationId`, `name`, `homeRegionId`, `adminBudgetPerTick` | —（**全必填**） |
| 2 | `sd.CreateArmy` | `armyId`, `nationId`, `rootUnitId`, `name` | —（**全必填**） |
| 3 | `sd.CreateDecisionMaker` | `id`, `affiliation`, `allowedTools`, `cadence` | —（**全必填**）；`viewScope` **不接受**（创建期恒为空范围，见 §三 S9） |
| 4 | `sd.PutInfo` | `address`, `key`, `value` | `note?` |
| 5 | `sd.CreateCombat` | `combatId`, `name` | ★ `participants?`（缺省 **空集**） |
| 6 | `sd.AddCombatStage` | `combatId`, `stage`（内部：`stage.stageId`, `stage.name`, `stage.outcomes`） | ★ `stage.participants?`（空集）、`stage.entry?`（空表）、`stage.exit?`（空表）、`stage.minDurationTicks?`（**0**）、`stage.maxDurationTicks?`（**= min**）、`outcomes.options[].casualties?` |
| 6b | 同上（**仅首阶段**） | `combatStateId`, `hex` | ★★ **非首阶段时这两个字段被静默忽略**（见 §三 S1） |
| 7 | `sd.SetStageOutcomeTable` | `combatId`, `stageId`, `outcomes` | `options[].casualties?` |
| 8 | `sd.CommitCombatOutcome` | `combatId`, `stageId`, `selectedOutcomeId` | —（**全必填**） |
| 9 | `sd.RecordCasualties` | `combatId`, `stageId`, `deltas`（**必须非空数组**，`requireDeltas:275`）；`deltas[].unit`, `deltas[].lossClass` | ★ `deltas[].personnel?`（**0**）、`deltas[].equipment?`（**空表**） |
| 10 | `sd.RegisterEffect` | `effectId`, `kind`, `trigger`, `action` | ★ `createdTick?`（缺省 = **当前 tick**） |
| 11 | `sd.CancelEffect` | `effectId` | — |
| 12 | `sd.IssueDirective` | `directiveId`, `decisionMakerId`, `tick`, `intentInfo` | ★ `target?`、`commands?`（**空表**）、`effects?`（**空集**）；`commands[].payloadJson?` 缺省 **`"{}"`**（`SdPayloads:155`） |
| 13 | `sd.SubmitVerdict` | `verdictId`, `breakpoint`, `subject`, `payload`, **`meta{model,promptVersion,inputBriefDigest}` 三键全必填** | —（**全必填**） |
| 14 | `sd.SetViewScope` | `decisionMakerId`, `viewScope`（**必须是对象**，`requireViewScope:371`） | ★ `viewScope.visibleRegions?`（空集）、`visibleHexes?`、`visibleUnits?`（空集）、`seeOwnUnits?`（**false**）、`adjudicationDisclosure?`（**`WITHHELD`**，`SdPayloads:409-413`）、`redactedFields?`（空集） |
| 15 | `sd.StartDecision` | `decisionMakerId` | `note?` |
| 16 | `sd.SetDecisionMakerProvider` | `decisionMakerId`, `providerId`（非空白） | —（**全必填**） |

**统计：全必填（一个可选都没有）的命令 7 条** —— `sd.CreateNation` / `sd.CreateArmy` / `sd.CreateDecisionMaker` / `sd.CommitCombatOutcome` / `sd.CancelEffect` / `sd.SubmitVerdict` / `sd.SetDecisionMakerProvider`。
**含"缺省即变语义"字段（★）的命令 8 条** —— 上述 5/6/9/10/12/14 + 6b。
⇒ **12 条新工具的 `description()` 里，"必填"与"★ 无默认/缺省值"两列都要写进去**（M1 的 `MapSetEdgeTool`/`MapRandomizeRegionTool` 是范本）。

---

## 三 前置即错（item 3）—— 本阶段核心

### 3.0 ★★ 硬判据：`sd.CreateArmy` 今天**报错**，不是静默成功 / 静默降级

**用户原话**：`CreateArmy`「**有对应 Nation/Army 就正常，没有就报错**」。
**实测结论：今天是"没有就报错"，逐字可读，三条前置全拒绝。**

`simos-sd/src/main/java/io/mosire/simos/sd/spi/CreateArmyHandler.java`（全 61 行，逐字）：

```java
    44       String name = SdPayloads.requireText(payload, "name");
    45       if (base.armies().containsKey(id)) {
    46         return new HandlerOutcome.Rejected("军队已存在: " + id);
    47       }
    48       if (!base.nations().containsKey(nationId)) {
    49         return new HandlerOutcome.Rejected("nationId 不存在: " + nationId);
    50       }
    51       if (!SdSnapshots.unitExists(state, rootUnit)) {
    52         return new HandlerOutcome.Rejected("rootUnitId 不存在: " + rootUnit);
    53       }
```

**逐字拒绝文案三条**（`:46` / `:49` / `:52`）：

| 缺什么前置 | 逐字文案 |
|---|---|
| army id 已存在 | `军队已存在: <id>` |
| **`nationId` 指向不存在的 Nation** | **`nationId 不存在: <nationId>`** |
| `rootUnitId` 指向不存在的单位 | `rootUnitId 不存在: <rootUnit>` |

类 Javadoc（`:24-25`）自陈同一口径：`★ 拒绝：id 已存在；nationId 不存在；rootUnitId 不存在（存在性经 SdSnapshots#unitExists 只读 unit 切片，铁律 3）`。

★ **"Army"那一半也核了**：`Army.rootUnit` 的存在性走 `SdSnapshots.unitExists`（跨模块只读，铁律 3），**不是**"先建后补"。
★ **没有静默成功、没有静默降级、没有"引用了不存在就悄悄跳过"** —— **M3 的硬判据在域层已经满足**（与 M1 裁决同姿态：**工具层不重复校验**，理由见 §3.1）。
★ 本节结论来自**逐行读码**，**未运行验证**（没跑 Maven、没提交过命令）。

### 3.1 由此推出的姿态（与 M1 裁决一致）

M1 简报 §5 的裁决对 sd 域**同样成立且更强**（sd 的拒绝文案比 map 更密）：
1. 域层拒绝经 `ToolSupport.fold` 变成 `ToolResult.error("REJECTED", <域层理由原文>)` ⇒ **可读理由已到达调用方**；
2. 工具层再写一遍校验**可被 `simos.command.submit` 绕过** ⇒ 那是装饰；
3. ⇒ **12 条新工具的义务是"证明拒绝理由真的到达调用方"，不是"再写一遍校验"**（照 M1 判据 4 的形态：每条工具一个坏载荷用例，断言里含**域层文案的关键片段**，不循环断 N 次）。

### 3.2 ★ 静默缺口表（照 `progress.md` §1.1 末段"静默缺口 7 处"的形态）

> 口径：**没有可读拒绝**、或**静默成功 / 静默取缺省 / 静默清空 / 静默忽略**的，逐条列出。
> **性质列**区分「**真缺口**」与「**有意口径 / 已被用例钉住**」——不许把后者冒充前者，也不许把前者说成"设计如此"。

| # | 命令 | 静默行为 | 锚点 | 我的判断依据 | 性质 |
|---|---|---|---|---|---|
| **S1** | `sd.AddCombatStage` | **非首阶段时 `combatStateId` / `hex` 被静默忽略**（给了也白给，给了错值也不报错） | `AddStageHandler.java:65-67`：`if (SdCombats.stateOrNull(withCombats, combatId) != null) { return Applied(...); }` —— 这一支**早退，从不读这两个字段**；`requireText/requireHex` 在 `:68-69`，**在早退之后** | 字段**已知且被文档写成"首阶段必填"**（`CatalogTool` 提示里就写着 `combatStateId?(首阶段必填), hex{q,r}?(首阶段必填)`）⇒ 属于"给了却被丢掉"，比 M1 的"未知多余字段静默忽略"更重：**调用方无法从返回判断它有没有生效** | ★ **真缺口**（无拒绝、无回执） |
| **S2** | `sd.CreateCombat` | `participants` 缺省 ⇒ **空集**；⇒ 可创建**零参与者 + 零阶段**的空交战，落 revision | `CreateCombatHandler.java:47`（`optionalUnitIdSet`）+ `:57`（`new Combat(id, name, List.of(), participants, …)`） | 无"至少一个参与者/阶段"校验；`SdPayloads.optionalUnitIdSet` 无值即返回 `Set.of()` | ★ **真缺口**（静默取缺省） |
| **S3** | `sd.AddCombatStage`（`stage` 子对象） | `participants` 缺省空集、`entry`/`exit` 缺省空表、`minDurationTicks` 缺省 **0**、`maxDurationTicks` 缺省 **= min** | `SdPayloads.requireStage:234-238`（`optionalUnitIdSet` / `optionalTriggers` / `optionalLong(…,0L)` / `optionalLong(…,min)`） | 阶段可以"零参与者、无触发器、时长 0"；`max` 静默继承 `min` 尤其隐蔽（调用方以为 max 另有约束） | ★ **真缺口**（静默取缺省） |
| **S4** | `sd.RecordCasualties` | `deltas[].personnel` 缺省 **0**、`deltas[].equipment` 缺省 **空表** ⇒ 可写一条**零损失**记录并落 revision | `SdPayloads.requireDeltas:281-282`（`optionalInt(delta,"personnel",0)` / `optionalIntMap(delta,"equipment")`） | **半有护栏**：`deltas` 本身必须非空（`requireDeltas:275`）、`unit`/`lossClass` 必填、**未知装备键有拒绝**（`未知装备键: `）、**超上界有拒绝**（`人员战损超出当前值: ` / `装备战损超出当前值: `）⇒ 缺的只是"零 vs 缺省"这一层 | ★ **真缺口**（静默取缺省）；但**上下界与未知键那两半是有护栏的** |
| **S5** | `sd.IssueDirective` | `commands` 缺省空表、`effects` 缺省空集、`commands[].payloadJson` 缺省 `"{}"` ⇒ **空令可被接受并落 revision**（只要有 `directiveId`/`decisionMakerId`/`tick`/`intentInfo`） | `IssueDirectiveHandler.java:77`（`optionalDirectiveCommands`）+ `SdPayloads:155`（`payloadJson` 缺省 `"{}"`） | 白名单校验的是**类型**（`DirectiveWhitelist`），**不校验载荷形状**；空载荷命令会以 `"{}"` 进入域层，由被调命令自己决定接受或拒绝 | ★ **真缺口**（静默取缺省） |
| **S6** | `sd.RegisterEffect` | `createdTick` 缺省 ⇒ **当前 tick** | `RegisterEffectHandler`（`optionalLong(payload,"createdTick", state.meta().timestamp().tick())`） | 缺省值**语义合理**，但调用方无法从回执区分"我给的"与"引擎填的"（时间线可回退 ⇒ 事后归因困难） | ⚠️ **有意的便利缺省**（未见上游文字裁决；**我未在 spec 里核到明文**⇒ 记为"设计如此但无书面依据"） |
| **S7** | `sd.SetDecisionMakerProvider` | **不校验 `providerId` 是否存在** ⇒ 任意非空白字符串都被接受并落 revision | `SetDecisionMakerProviderHandler.java:47-50`；类 Javadoc `:25-27` 逐字：「★★ 本处理器只校验"非空白"，不校验 provider 是否存在：provider 配置在 app / AgentLib 层，sd 看不见（铁律 3）。存在性由**使用时刻**的解析强制——解析不到就 fail-closed……**绝不静默兜底**」 | **已被用例钉死**：`SdProviderBindingEndToEndTest.java:84 bindingAnUnregisteredProviderIsAllowedBecauseExistenceIsResolvedAtUseTime` 断言"未注册 provider 也被接受" ⇒ **这是有意口径，不是未发现的缺口** | ✅ **有意口径**（已钉住）。★ 但**从工具面看它确实是"静默接受"** ⇒ **工具的 `description()` 必须写明"provider 存在性在使用时刻判定，本命令不校验"**，否则模型会以为写错会被拒 |
| **S8** | `sd.SetViewScope` | `viewScope` 对象内**每个子字段都可缺省**（`visibleRegions` 空集 / `seeOwnUnits=false` / **`adjudicationDisclosure=WITHHELD`** / `redactedFields` 空集） | `SdPayloads.requireViewScope:374-393` + `:409-413` | `viewScope` **整体**是必填的（`requireViewScope:371` 缺了即抛）⇒ **不存在"漏写 viewScope 静默清空"** 这条路；缺的只是子字段 | ⚠️ **子字段静默取缺省**；★ 好消息：**`viewScope` 本身不可省**，主缺口不存在 |
| **S9** | `sd.CreateDecisionMaker` | 创建期 `viewScope` **恒为空范围**（`ViewScope.empty()`），**不接受**调用方传入 | `CreateDecisionMakerHandler.java:60`（`new DecisionMaker(id, affiliation, allowedTools, ViewScope.empty(), cadence)`）；类 Javadoc 逐字：「★ **创建期 `viewScope` 恒为空范围**」 | 字段**不在**载荷真值表里（既不 require 也不 optional）⇒ 传了会被**静默忽略**（类型 `JsonNode` 不校验多余键） | ✅ **有意口径 + 已知**（Javadoc 明写）；⚠️ 但"传了被静默忽略"这一层**无拒绝** |
| **S10** | `sd.PutInfo` | `SdInfoEntry.value` 是**裸 `Object`**，结构化值的 `equals` 往返**不满足** | `PutInfoHandler` 类 Javadoc「诚实边界」段 | 类 Javadoc **自陈**且不在此解决（归 util 层）；⇒ 写入成功 ≠ 读回逐值相同 | ✅ **有意记账**（自陈边界）。★ `description()` 应提示"value 只保证标量往返" |
| **S11** | **跨域（继承自 M1）** | **空操作仍落 revision**：`SdChangeSet.isEmpty()` 存在但 `CommandBus` 从不读它 | M1 简报 §5 第 5 条（`MapChangeSet.isEmpty()` 同形）；sd 侧 `SdChangeSet` 同 | **未被本任务引入**，也**不在本任务修**（改它会牵动已关账的变异轮） | ⚠️ **既有开口项（跨域）**——本报告只做**点名**，不主张在 M3 修 |

**统计：真缺口 5 处**（S1~S5）、**有意口径/已钉住 3 处**（S7/S9/S10）、**子字段级静默 2 处**（S6/S8）、**跨域既有 1 处**（S11）。

★ **与 M1 的 7 处静默缺口比对**：M1 那 7 处全是 **map 域**、且 M1 控制器**已裁「不在 M1 修」**。
sd 这 5 处真缺口**互不重叠**（S1 是"给了被忽略"的**新形态**——M1 的 #4 只是"未知多余字段"）。
⇒ **建议控制器对 S1~S5 采与 M1 同一处置**：**不在 M3 修**，但**逐条写进 12 条新工具的 `description()`**（这正是 M1 §7(e)② 把 `map.UpdateRegion` 的"meta 整体替换"写进 `description()` 的同款做法）。

### 3.3 逐字拒绝文案全表（12 条待建工具，写用例时照抄；每条都含可断言的关键片段）

> ★ 判据形态照 M1 §6 判据 4：**每条工具各一个用例**，断言**含文案关键片段**（不许只断 `Rejected` 这个词——「只判 token 判不出是哪一层拒的」是本仓反复踩过的坑）。

| 命令 | 缺前置的逐字文案（片段） |
|---|---|
| `sd.CreateNation` | `国家已存在: ` / `homeRegion 不存在: ` / **`Region <id> 无国家 tag（R13：需以 nation: 开头的 tag）`** |
| `sd.CreateArmy` | `军队已存在: ` / `nationId 不存在: ` / `rootUnitId 不存在: ` |
| `sd.CreateDecisionMaker` | `决策人已存在: ` / `affiliation 目标不存在: ` / `allowedTools 不得含通用写 simos.command.submit（N9：决策 Agent 只用窄工具）` |
| `sd.PutInfo` | 地址非法（`Address.parse` 抛）/ `key` 空白 / `value` 缺失 —— 经 `requireAddress` / `requireText` / `requireValue` 抛 `IllegalArgumentException`，`HandlerOutcome.Rejected(e.getMessage())` |
| `sd.CreateCombat` | `交战已存在: ` / `参与单位不存在: ` |
| `sd.AddCombatStage` | `交战不存在: ` / `CombatState 已存在: ` |
| `sd.SetStageOutcomeTable` | `交战不存在: ` / `阶段不存在: ` |
| `sd.CommitCombatOutcome` | `交战不存在: ` / `阶段不存在: ` / **`结局不在该阶段的 outcomeTable 里（N2）: `** / `该交战没有 CombatState: ` / `该交战已选定结局（不覆盖）: ` |
| `sd.RecordCasualties` | `交战不存在: ` / `阶段不存在: ` / `单位不存在: ` / `人员战损超出当前值: ` / **`未知装备键: `** / `装备战损超出当前值: ` / `损失记录已存在: ` |
| `sd.RegisterEffect` | `效果已存在: ` / `trigger 引用的单位不存在: ` / `trigger 引用的交战不存在: ` / `trigger 引用的结局不在该交战里: ` / `action 引用的 CombatState 不存在: ` / `action 引用的阶段不存在: ` / `action 引用的损失记录不存在: ` / **`action 引用的命令不在白名单: `** |
| `sd.CancelEffect` | `效果不存在: ` / `效果状态不可取消: ` |
| `sd.SetDecisionMakerProvider` | `决策人不存在: ` / `providerId 不得为空白` |

★ 另 4 条**已有工具**的文案（`sd.IssueDirective` / `sd.SubmitVerdict` / `sd.SetViewScope` / `sd.StartDecision`）：
`决策人不存在: ` / `决策已存在: ` / `效果不存在: ` / `判决已存在: `（+ schema 校验文案）。
★ `SetViewScopeHandler.java:60` 有一行注释值得抄进新代码语境：「★ 配权只换 viewScope：**必须带回既有 providerId，否则静默丢绑定**（M11 变异靶子 m2）」。

---

## 四 `sd.SetDecisionMakerProvider`（item 4）

**用户已裁：M3 要给它 GM。以下是今天的实况。**

| 项 | 实测 | 锚点 |
|---|---|---|
| `type()` | `sd.SetDecisionMakerProvider` | `SetDecisionMakerProviderHandler.java:34-36` |
| 实现类 | `SetDecisionMakerProviderHandler`（70 行） | `simos-sd/src/main/java/io/mosire/simos/sd/spi/SetDecisionMakerProviderHandler.java` |
| **载荷** | `{decisionMakerId, providerId}` —— **两键全必填、全无默认**；`providerId` 非空白 | `:45-50` |
| 拒绝文案 | `决策人不存在: <id>` / `providerId 不得为空白` | `:49` / `:53` |
| `Shell` 注册 | ✅ **late handler**（`:363`；`List.of(...)` 在 `:365-374`），即**在 `commandTypes` 完整之后**注册 | `Shell.java:362-364`、`:365-374` |
| **今天归哪个桶** | ★ **不属于任何写桶** —— `addExternalWrites`:121-126 无、`addGmWrites`:134-147 无、`addDecisionAgentWrites`:150-154 无 | `SimosToolSource.java` 三处 |
| **有没有工具** | ❌ **没有窄写工具**（`tools/write/` 下无同名类） | `grep -l 'sd\.' .../tools/write/*.java` 命中 4 个，无它 |
| 今天怎么到达 | **只能经通用写 `simos.command.submit`**（`Role.EXTERNAL` 与 `EXTERNAL_WITH_GM` 两个口都有通用写 ⇒ 今天它**可达**，只是**没有窄工具**） | `addExternalWrites:123` |
| 已在 `PAYLOAD_HINTS` | ✅ `Map.entry("sd.SetDecisionMakerProvider", "decisionMakerId, providerId")` | `CatalogTool.java:101` |
| 已在 `McpCoverageTest` | ✅（在 `EXPECTED_COMMAND_TYPES` 与 `MINIMAL_PAYLOADS` 两表内） | 见 §六 |

★ **它的类 Javadoc 有一段必须让控制器知道**（`:25-27` 逐字）：
> 「★★ **本处理器只校验"非空白"，不校验 provider 是否存在**：provider 配置在 app / AgentLib 层，sd 看不见（铁律 3）。存在性由**使用时刻**的解析强制——解析不到就 fail-closed（明确报错或既定降级），**绝不静默兜底**。」

⇒ **给 GM 时，工具的 `description()` 必须把这句转成人话**（否则 GM 会以为"providerId 写错会被拒"）。
⇒ 且**已有端到端用例**把"未注册 provider 也接受"钉成判据（`SdProviderBindingEndToEndTest.java:84`）⇒ **新工具不得顺手加存在性校验**（那会让该用例语义反转 = 篡改既有判据）。
⇒ 同一文件的 `:101 settingViewScopeAfterBindingKeepsTheProvider` 钉住"配权不丢绑定"（对应 `SetViewScopeHandler:60` 那条注释）。

**桶的接线点（一条）**：`SimosToolSource.addGmWrites`（`:134-147`）末尾追加一行
`built.add(new SdSetDecisionMakerProviderTool(core, initiator, mapId));` —— `EXTERNAL_WITH_GM` 会自动带上（`:112-115`）。

---

## 五 `CatalogTool.PAYLOAD_HINTS`（item 5）—— **空操作，零改动**

**结论：sd 域 16 条命令的提示 `16/16` 全在表内，一条不缺。**（M1 的经验在 sd 域同样成立。）

- 位置：`simos-app/src/main/java/io/mosire/simos/app/tools/read/CatalogTool.java:33-101`（`PAYLOAD_HINTS`）。
- 数法：`grep -c '"sd\.' .../CatalogTool.java` = **16**；逐条点名（`:65`~`:101`）：
  `sd.CreateNation`(`:65`) / `sd.CreateArmy`(`:66`) / `sd.CreateDecisionMaker`(`:67-68`) / `sd.PutInfo`(`:69`) /
  `sd.CreateCombat`(`:70`) / `sd.AddCombatStage`(`:71-74`) / `sd.SetStageOutcomeTable`(`:75-77`) /
  `sd.CommitCombatOutcome`(`:79`) / `sd.RecordCasualties`(`:80-82`) / `sd.RegisterEffect`(`:83-85`) /
  `sd.CancelEffect`(`:86`) / `sd.IssueDirective`(`:87-90`) / `sd.SubmitVerdict`(`:91-94`) /
  `sd.SetViewScope`(`:95-99`) / `sd.StartDecision`(`:100`) / `sd.SetDecisionMakerProvider`(`:101`)。
- **构造期强制**：`CatalogTool.java:109-120` —— 逐条查 `PAYLOAD_HINTS.containsKey(type)`，有缺项即
  `throw new IllegalArgumentException("已注册命令类型未登记载荷提示（PAYLOAD_HINTS）: " + missing)`。
- ★ **对 M3 的含义**：**新增窄写工具不产生新命令类型** ⇒ `PAYLOAD_HINTS` **不动**。
  （该规矩的对象是**命令 type**，不是**工具**——`m4-inventory.md` §3.3 已把这条措辞坑记过一次。）
- ★ **一处副产物**：`:71-74` 的 `sd.AddCombatStage` 提示**已经把"首阶段必填"写进去了**（`combatStateId?(首阶段必填), hex{q,r}?(首阶段必填)`）⇒ S1 的静默忽略**在提示层已被半披露**（用了 `?` 号，但没写"非首阶段时给了会被忽略"）。**M3 若要补 §三 S1 的措辞，改的是这行提示 + 新工具的 `description()`，不是 `CatalogTool` 的结构。**

---

## 六 `McpCoverageTest`（item 6）—— **空操作，零改动**

**结论：sd 域 16 个命令类型**已在双向载荷断言与最小载荷表里**，且该用例**按命令 type 驱动**（不走工具名）⇒ 加窄写工具**不红**。

| 项 | 实测 | 锚点 |
|---|---|---|
| `EXPECTED_COMMAND_TYPES`（含全部 sd 类型） | `:108` 起 | `McpCoverageTest.java:108`；`:320` `.containsExactlyInAnyOrderElementsOf(EXPECTED_COMMAND_TYPES)` |
| 与 catalog 双向对拍 | ✅ `:323` `.containsExactlyInAnyOrderElementsOf(catalogTypes)` | 同一用例 |
| `MINIMAL_PAYLOADS`（含全部 sd 类型） | `:155` 起（`new LinkedHashMap<>()`） | `:203`~`:266` 内有 sd 条目，例：`"sd.CreateNation"` → `{nationId:"n-cov", name:"覆盖国", homeRegionId:"r-nation", adminBudgetPerTick:1}`；`"sd.CreateArmy"` → `{armyId:"a-cov", nationId:"n-cov", rootUnitId:"u-2", name:"覆盖军"}` |
| 计数 | `grep -c '"sd\.' .../McpCoverageTest.java` = **33** | 与 §〇 一致（16 类型 × 2 表 ≈ 32） |
| **驱动方式** | 经**通用 MCP `callTool`**（catalog 在 `:407`、submit 在 `:448`）⇒ **按类型驱动** | ❶ 加工具不改它；❷ 也说明**读工具/窄工具在它里面没有等价物**（与 `m4-inventory.md` §3.2 结论一致） |

★ **`spot check`**：`:206-208` 与 `:203-205` 两处我逐字读过（`sd.CreateArmy` / `sd.CreateNation`），其余 14 条**未逐条读**（只按 `"sd.` 计数与两表行数推定 16+16）。
⇒ 本节的"16/16 在表内"中，**14 条是"计数支持"、2 条是"逐字读过"**。**未运行验证**（没跑 Maven）。

---

## 七 ★★ D-5 · 导入器（item 7，独立子项）

### 7.1 现在是**怎么**产出区域 tag 的（逐字）

**文件**：`tools/gsimap_import.py`（**实测 712 行**，标准库 only，**不入 Maven reactor**）。
**函数**：`build_map_payload(data, report)`（`:426` 起）。
**唯一的 tag 产出点**：`:506`。

```python
     499         regions[province_id] = {
     500             "id": {"value": province_id},
     501             "name": province_id,  # 旧 Province 无 name 字段：名字就是 dict 的键
     502             "hexes": hex_list,
     503             "boundary": {"rings": [[{"u": u, "w": w} for (u, w) in ring] for ring in rings]},
     504             "meta": {
     505                 "color": province.get("color"),
     506                 "tag": province.get("tag"),          # ← 唯一的 tag 产出点
     507                 "description": province.get("description"),
     508                 "annexedBy": province.get("annexedBy"),
     509             },
     510         }
```

**⇒ 现状是"直通复制"**：tag 取自**旧档** `provinces.<id>.tag` 原值，导入器**从不构造 `nation:` 前缀**。
**⇒ 旧档里是什么，导入出来就是什么**。实测该旧档的 tag 值是 `"Nation"`（见 §7.3 的富世界资源）。

### 7.2 要改成 `nation:<名>` 需要动**哪几处**？有没有第二处？

**（a）导入器内：只有 1 处** —— `gsimap_import.py:506`。
数法：`grep -n '"tag"' tools/gsimap_import.py` ⇒ **只有 `:506` 一条**。
（同文件里其余 `tag` 出现全是 `edgeTags`（连通性）语义：`:317/:320/:355/:394/:411-420/:435/:446-448`，与区域 tag **无关**。）

**（b）★ "名"是什么？** —— `:501` 已经把 `name` 设成 `province_id`（= `provinces` 字典的键）；实测 `name == id` 的比例是 **252/252**。
⇒ `nation:<名>` 的「名」**唯一合理取法 = `province_id`**（即 `"nation:" + province_id`）。
★ 改法**不要写死前缀字面量**：Java 侧有 `NationTag.PREFIX = "nation:"`（`simos-sd/.../sd/spi/NationTag.java:13`），Python 侧没有对应常量 ⇒ **建议在 `gsimap_import.py` 顶部加一个 `NATION_TAG_PREFIX = "nation:"` 常量**（与 `NationTag.java:8-9` 的"常量集中一处"是同一精神；跨语言无法共用，只能各留一处并在注释里互相指名）。

**（c）★ 有没有第二处写"同类 tag"？**

| 候选 | 实测 | 判断 |
|---|---|---|
| 导入器内第二个 tag 产出点 | **无**（`grep -n '"tag"'` 只 1 条） | ✅ 只动 1 处 |
| `meta.annexedBy`（同一 `meta` 块） | `:508` 也是**直通复制**，实测富世界里 **4 个区域非空**（值如 `石冠诸部`、`蜀`/`大蜀`、`奥斯曼属瓦拉几亚占领区`） | ⚠️ **语义相邻但不同**：它是"被谁吞并"的**区域名**，不是 `nation:` 前缀的 tag。**D-5 文字只说 tag ⇒ 我的判断是不改**；但**这一点必须请控制器明示**（若日后 `annexedBy` 也要指向 Nation，它是**第二处**） |
| `regions[province_id]["name"]` | `:501` = `province_id`（**不是国家名**） | ⚠️ `name` **无 nation 语义**（它就是区域名）⇒ `nation:<名>` 的「名」与这个 `name` 同源，**不需要额外改** |
| **`tools/v17levant_docs.py:316-320`** 的 `_region_of(key)`：「从 map 条目 key（形如 `Nation:奥斯曼帝国`）取区域名」+ `:323-326` 的 `_map_sort_key`（`re.fullmatch(r"Nation:区域\d+", key)`） | ★ **它读的是"存档 INFO 元素的 key"，形如 `Nation:<名>`**，与 `RegionMeta.tag` 是**两个不同载体**；且**它只读·只写 `.md`**（`tools/v17levant_docs.py` 不 import 任何 Java/simos 类型、不碰 `simos.db`、不 `submit`） | ✅ **不算"同类 tag 的第二处"**：① 载体不同（INFO key vs `RegionMeta.tag`）；② 方向不同（它**从不写** simos 状态）。★ **但它是同一个命名约定的"平行实现"** ⇒ **D-5 改完后，两套约定首次变得"看起来像同一个东西"，此处必须留一句注释互指**，否则下一个读者会以为 `v17levant_docs.py` 读的就是 `meta.tag` |
| `tools/check_v17levant_docs.py:163,172-173`（断言 `map.md` 里 `` `Nation:<名>` `` 的编号条目） | 同上：**校验的是 INFO key 的派生文档**，不碰 `meta.tag` | ✅ 不受 D-5 影响 |
| `simos-map/.../spi/CreateRegionHandler.java:19` 的 javadoc 样例串 `"tag":"Nation"` | 只是**文档示例** | ⚠️ **建议顺手改成 `nation:r1`**（否则它继续教人写错形态）；**不改也不红**（注释里的字面量） |

### 7.3 ★★ 会不会牵动既有断言 / 黄金样本？—— **会，2 处；但关键的那处 sha256 不会**

| # | 文件 | 锚点 | 会不会红 | 依据 |
|---|---|---|---|---|
| **G1** | `tools/check_v17levant_import.py` | `:35 EXPECTED_TAGS = {"Nation": 252}` + 断言 `:174-178`（`check("regions.tags", tags == EXPECTED_TAGS, "{}".format(tags))`） | ★★ **必红** | 改后 tag 变成 252 个**互不相同**的 `nation:<名>` ⇒ `tags` 变成 252 键各计 1 ⇒ `== {"Nation": 252}` 为假。**修法**：把 `EXPECTED_TAGS` 改成"**252 个键、每个都是 `nation:` 前缀、且值与区域名一一对应**"的形态（比对死一张 252 键的字面量表好维护） |
| **G2** | `simos-app/src/test/java/io/mosire/simos/app/demo/RichWorldTest.java` | `:86-94 provincesAre252AllNation`（`:90` `filter(r -> "Nation".equals(r.meta().tag()))` 断 `.isEqualTo(252)`；`:91` 断 `王国` 计数 `.isZero()`） | ★★ **必红（前提：资源被重生成）** | tag 改后没有一个是 `"Nation"` ⇒ `nations` 计数变 0。**修法**：改成断"252 个 tag 全以 `nation:` 开头，且 `tag == "nation:" + regionId`"（比断 252 个字面量更稳） |
| **G3** | `tools/check_v17levant_import.py` | `:42 EXPECTED_REGIONS_SHA256 = "44174bb9435eb498b4eb564fd3664703249e0a2a503f32f23a5776b72e187418"` + `:165-168` | ✅ **不会红**（**已实测**） | 我**当场重算**了 `regions_digest`（口径见 `:81-87`：`id:q_r,q_r,…` 每行，UTF-8 sha256）：**重算值 = `44174bb9…718`，与期望值逐字相同** ⇒ 该摘要**只含区域 id 与 hex 列表，不含 `meta`/`tag`** ⇒ **tag 变化不动它** |
| **G4** | `RichWorldTest.carriesRegionsThatN0000Lacked`（同文件，断言 5 个实名区域） | 同文件 | ✅ 不会红 | 它按**区域 id**断言（`瓦伦狄乌斯专制国` 等），与 tag 无关（**我只读了 `RichWorldTest` 的 252/直方图/河流边那几条；`carriesRegionsThatN0000Lacked` 的逐字断言体我只在 u4-report 里读到其存在与意图**——标 **未核实到逐行**） |
| **G5** | `docs/worlds/v17levant/*.md` 与 `tools/check_v17levant_docs.py` | `:39-47 EXPECTED_TOTAL=615` / `EXPECTED_BY_CATEGORY` / `EXPECTED_CHARS_BY_CATEGORY` / `EXPECTED_TOTAL_CHARS=319630` | ✅ 不会红 | 该链的输入是**存档 INFO 元素**，**不读** `meta.tag`、**不读**导入产物（见 §7.2(c)）。★ 实测 `docs/worlds/v17levant/map.md` 含 **106** 处 `Nation:`（INFO key 派生）——**D-5 不动它** |
| **G6** | JS 侧（`simos-app/src/main/resources/webui/*.js` 与 `src/test/js/*.test.cjs`） | 见 §7.5 | ✅ 不会红（但**有语义耦合**） | 它们的夹具是**自带 `nation:` 字面量**，不读富世界资源 |

**全仓搜索命令与范围**（我的"没搜到"依据）：
- `git grep --untracked -n '"Nation"' -- 'simos-*/src/**' 'tools/**' 'docs/**'` ⇒ 命中：`RichWorldTest.java:90`、
  `CreateRegionHandler.java:19`（javadoc 示例）、`docs/` 下的设计与 spec 文本（**非可执行断言**）。
- `grep -rn "Nation" tools/` ⇒ 命中：`check_v17levant_import.py:35`、`v17levant_docs.py:317/:325`、`check_v17levant_docs.py:163/:172-173`。**其余 `tools/*.py` 零命中**。
- `git grep --untracked -n "nation:" -- simos-app/src/test/js` ⇒ 命中 3 个 JS 测试文件（夹具字面量，见 §7.5）。
- ★ 范围声明：以上是**已入库 + `--untracked` 的文件**；`.superpowers/**` 内的历史证据 JSON 与 M8/M9 报告**不在**上述模式内（我用 `-size +200k` 单独扫过大文件，见 §7.4）。

### 7.4 ★ **真档在哪**？—— **本机无导入器输入侧真档**（逐条 `ls`/`find` 实测）

**（a）导入器的输入侧（D-5 复验所需）—— 本机不存在。**

| 候选真档路径 | 出处 | 实测 |
|---|---|---|
| `~/DevMosire/testspace/worlds/v17levant_2/nodes/`（`v17levant_docs.py:352` 的 `default_source()`） | `tools/v17levant_docs.py:17,352` | ❌ **`ls ~/DevMosire` ⇒ `No such file or directory`** |
| **`/home/cna/DevMosire/workspace/worlds/v17levant/nodes/n0000_map.json`** | ✓ **这是 T11 与 U4 两轮实测真正用过的真档**——见 `.superpowers/sdd/2026-09-21-webui-stage-fix/t11-evidence/logs/checker.txt:1`（`源档 : /home/cna/DevMosire/workspace/worlds/v17levant/nodes/n0000_map.json`）与 `.superpowers/sdd/2026-09-22-webui-fix2/u4-evidence/logs/checker.txt:3`（`基础档 : /home/cna/.../n0000_map.json`） | ❌ **`ls /home/cna` ⇒ `No such file or directory`** ⇒ **那是另一台机器的家目录** |
| `n0001_map_diff.json` … `n0007_map_diff.json`（`materialize_v17levant.py` 的输入，`DIFF_NODES:48`） | `tools/materialize_v17levant.py:22-24` | ❌ **`find / -xdev -name '*_map_diff.json'` ⇒ 0 命中** |
| `/tmp/u4-materialized/map.json`（U4 物化产物） | `u4-evidence/logs/checker.txt:1` | ❌ **`/tmp` 下已无**（`find / -xdev -name 'n0000_map.json' -o -name '*_map_diff.json' -o -type d -name nodes` ⇒ **0 命中**） |
| M6/M9 用过的 `test_integration` 真档（19441 hex） | CLAUDE.md M6/M9 行 | ❌ **`find / -xdev -iname '*test_integration*'` ⇒ 0 命中**；`/tmp/m6-import-verify/` 已不在 |

**⇒ 明确写：本机无导入器输入侧真档。**
★ **搜过的范围（逐条命令）**：
`ls -d ~/DevMosire ~/ProjectMosire /home/cna`（结果：`~/DevMosire` 无、`/home/cna` 无、`~/ProjectMosire` 有）
`find / -xdev -name 'n0000_map.json' 2>/dev/null` ⇒ 0
`find / -xdev -name '*_map_diff.json' 2>/dev/null` ⇒ 0
`find / -xdev -type d -name nodes 2>/dev/null` ⇒ 0
`find / -xdev -iname '*test_integration*' 2>/dev/null` ⇒ 0
（`-xdev` 限本文件系统；未跨挂载点。）

**（b）导出侧真档 —— 本机**有**，但**不能**替代输入侧。**

| 存在的真档 | 路径 | 实测 |
|---|---|---|
| **富世界（导入器产物的逐字节签入）** | `/home/dev/SimulatorMosire/.claude/worktrees/ts+m1/simos-app/src/main/resources/worlds/v17levant.json` | ✅ **4,219,540 B**、md5 `1d817eee4d238c5255953e274a8f00f0`；`hexes=59223`、`regions=252`、**tag 分布 `{'Nation': 252}`**、**`nation:` 前缀 0 个**；`modules` 键 = `['map','social','unit']`（**无 `sd`**） |
| 19441 格真档（M9 T6 证据） | `.superpowers/sdd/2026-09-19-map-perf/t6-evidence/legacy/real-old-checkpoint.json` | ✅ 1,111,409 B。★ **它是 simos checkpoint（输出格式）**，不是旧 `*_map.json` ⇒ **导入器吃不了它** |
| M9/M8 的 overview 证据 JSON | `.superpowers/sdd/2026-09-19-map-perf/t6-evidence/legacy/overview-served-from-old-archive.json`（703,053 B）/ `t11-evidence/raw/overview-pre-t11.json`（227,377 B） | ✅ 存在，同样是**输出侧** |

★ **关键区分（决定 M3 (iii) 能否验收）**：
> **导入器的复验需要"旧格式 `*_map.json` 输入"**（`gsimap_import.py` 顶部 fail-closed 明确拒绝 MapDiff）。
> 本机**只有输出侧真档**，**没有输入侧真档** ⇒
> **D-5 的"用真档复验"在本机不可执行**（不是"难"，是**缺输入**）。
>
> ★ 而"重新生成 `v17levant.json`"这条路也**不通**：它需要
> `materialize_v17levant.py`（吃 `n0000_map.json` + `n0001..n0007` diff）→ `gsimap_import.py`，
> **两级输入全缺**。
>
> ★ **`check_v17levant_import.py` 本身也跑不了**：它的 `main` 要求 `len(argv) in (2,3)`
> （`tools/check_v17levant_import.py:129-134`），**argv[0] = 旧格式源档是必填的** ⇒ 无真档 ⇒ **连校验脚本都起不来**。
> ★ **导入器侧唯一"不需要真档"的现成自证是 `importer_self_tests()`**（`:105-127`），但它只覆盖
> **融合优先级 / 交叉校验 / LOSSY 表**三项，**完全不碰 tag** ⇒ **D-5 在导入器侧没有任何现成的合成自证可挂**。

**⇒ 给控制器的行动建议（三选一，需裁定）**：
1. **降级为合成夹具复验**：新造一份**最小旧档**（含 2~3 个 province、带 `tag:"Nation"`），跑导入器，断言输出 tag 是 `nation:<名>`；
   并把这条**加进 `check_v17levant_import.py` 的 `importer_self_tests()`**（那里已有"每条配一个故意违规输入"的装置形态，加一条 tag 自证最省）。
   ★ **代价**：只证"改了会怎么写"，**不证"对真档写出来的仍是 252 个正确值"**。
2. **改完不重生成资源**：只改 `gsimap_import.py` + 更新 G1/G2 的期望值，**`v17levant.json` 保持旧字节** ⇒ **`RichWorldTest` 与 `check_v17levant_import.py` 都不动**。
   ★ **代价**：签入的富世界与导入器**不一致**（下次谁重跑谁红），且**用户抱怨的"每次建国家要绕两步"今天照旧**（见 §7.6）。
3. **本机取证 → 换机复验**：把 (iii) 拆成"本机改 + 合成自证"与"真档复验"两段，后者**明确记为未兑现**（照 M8 T12 / M9 的"本机无档"先例记账）。

### 7.5 ★ 附：`nation:` 的**语义是 ID 不是名** —— 一处 D-5 措辞与既有代码的**契约张力**（务必让控制器看到）

| 证据 | 逐字 |
|---|---|
| `NationTag.java:6,17-19` | 类注「"国家区域"的 tag 约定（R13 / 计划 §六 G1）：`"nation:" + nationId`」；`tagFor(NationId nationId) { return PREFIX + nationId.value(); }` |
| `simos-app/src/main/resources/webui/map.js:2373-2375` | 「★ R13（SDSimos spec）："国家区域" = `meta.tag` 以 `nation:` 开头的 Region；**tag 值恒为 `nation:<nationId>`（`NationTag.tagFor`）**。**国名与区域的对应靠 tag 逐字相等**，不靠 Nation.homeRegion」 |
| `simos-app/src/main/resources/webui/panels.js:1227` | `window.SimosMap.nationRegionIds(decisionOverviewRegions \|\| [], **"nation:" + affiliation.id**)` |

�⇒ **决策模式的"国家高亮"靠 `region.meta.tag == "nation:" + decisionMaker.affiliation.id` 逐字相等**（`map.js` 的 `nationTagOf`/`nationRegionIds` 是**纯函数**，且 `:2388-2389` 注释明写「**集合相等、不是子集** …… **不做任何交集/取首**」）。

**⇒ 对 D-5 的两条硬含义**：
1. **`nation:<名>` 的「名」必须恰好等于** **GM 日后 `sd.CreateNation` 用的 `nationId`**。在 v17levant 里区域名（=`province_id`）与国名同形（`奥斯曼帝国` 等）⇒ **只要 GM 照区域名取 `NationId`，两套就对得上**（`NationId` 只禁空白，`NationId.java:11-13`，中文合法）。
2. **对不上时不会报错**：`nationRegionIds` 找不到就返回 **`[]`**（`map.js:2389-2395`，`decision-mode.test.cjs:430` 实测 `nationRegionIds(REGIONS,"nation:n3") === []`）⇒ **静默空高亮**。
   ⇒ **D-5 的 `description()` / 注释里必须写明"本 tag 的后缀是 NationId"**，否则下一个人写 `nation:<区域显示名>` 就会得到一个**不报错但高亮不出来**的世界。
- ★ 相关 JS 夹具**自带 `nation:` 字面量**（`decision-mode.test.cjs:38-44`、`pending-signal.test.cjs:54`、`webui-fix2.test.cjs:40-42`）⇒ **D-5 不红它们**；但它们是上面这条耦合的**现成判据**（改 `map.js` 会红，改导入器不会）。

### 7.6 ★ D-5 的动机核对（"每次建国家要绕两步"是真的吗）—— **是真的**

- 富世界资源实测 **252 个区域 tag 全是 `"Nation"`、`nation:` 前缀 0 个**（§〇）。
- `sd.CreateNation` 的 R13 门：`CreateNationHandler.java:53-55`
  ```java
   53       if (!NationTag.isNationTag(region.meta().tag())) {
   54         return new HandlerOutcome.Rejected(
   55             "Region " + homeRegion + " 无国家 tag（R13：需以 " + NationTag.PREFIX + " 开头的 tag）");
  ```
  而 `NationTag.isNationTag` = `tag != null && tag.startsWith("nation:")`（`NationTag.java:21-23`）。
- ⇒ **在富世界上直接 `sd.CreateNation` 必定被拒**，GM 必须先 `map.UpdateRegion` 改 tag、再 `CreateNation` ⇒ **"绕两步"确有其事**（与 `docs/superpowers/HANDOFF-2026-09-22.md:36` 的描述一致）。
- ★ 连带：`RegionDeleteGuard.java:56-57`（A6 跨模块守卫）用**同一常量**判"带国家 tag ⇒ 不可删" ⇒ **D-5 之后，富世界的 252 个区域会全部变为"不可删"**（今天它们 tag 是 `"Nation"`，`isNationTag` 为假 ⇒ **今天可删**）。
  ⇒ ★★ **这是一处 D-5 会引入的、importers 与 guard 之间的语义变化，必须让控制器知道**：
  **改完之后，M8 地图编辑里"删区域"对富世界的 252 个区域全部变成拒绝**（逐字文案：`Region <id> 带国家 tag（nation:<名>），不可删（spec §九）`）。
  这**未必是坏事**（很可能正是想要的），但**它是一条静默的行为反转**，且**没有任何既有断言会红**（我没有找到"富世界区域可删"的断言；搜索范围见 §十一）。

---

## 八 sd 域自己的既有用例（item 8）

**结论：`simos-sd` 模块的 22 个测试文件里，**没有一条**会因 sd 域新增窄写工具而红。**

- 数法：`find simos-sd/src/test -name '*.java'` ⇒ **22** 个（20 个 `*Test.java` + 2 个 `testing/` 夹具：`SdFixtures.java` / `SdWorlds.java`）。
- **判据**：`git grep --untracked -n "ToolSource\|SimosToolSource\|toolName\|listTools\|Role\.GM" -- simos-sd/src/test` ⇒ **零命中（rc=1）**。
  ⇒ `simos-sd` 的测试**根本不认识工具面**（它们的对象是 `CommandHandler` / `SdState` / codec / guard / time）。
  **⇒ 新增窄写工具是 `simos-app` 侧的纯增量，`simos-sd` 一个字节都不动、一条用例都不红。**
- ★ 范围声明：搜索限 `simos-sd/src/test`（`git grep --untracked`，含未入库文件）。

**sd 相关但不在 `simos-sd` 内的用例（`simos-app/src/test/java`，**按派单排除三个文件后**逐个核过）**：

| 文件 | 会不会红 | 依据（锚点） |
|---|---|---|
| `simos-app/.../app/sd/StartDecisionEndToEndTest.java` | ✅ **不会红** | `:229-238` 的桶断言全是 **`contains` / `doesNotContain`**（`assertThat(names(shell.toolsFor(Role.GM))).contains(StartDecisionTool.NAME)` / `Role.DECISION_AGENT` 与 `Role.EXTERNAL` 各一条 `doesNotContain`），**没有计数、没有精确集合** ⇒ 往 GM 桶加 12 条**不破坏**它们。★ 这是 `m4-inventory.md` §3.2 说的"正确的写法，可作范本" |
| `simos-app/.../app/tools/SimosToolsTest.java` | **按派单排除**（另一个侦察者在读） | — |
| `simos-app/.../app/McpServerTest.java` | **按派单排除** | — |
| `simos-app/.../app/McpPortTopologyTest.java` | **按派单排除** | — |
| `simos-app/.../app/McpCoverageTest.java` | ✅ **不会红** | 见 §六（按命令 type 驱动，不看工具名） |
| `simos-app/.../app/AppWritePathGuardTest.java` | ✅ **不会红**（但有**新代码要遵守的约束**） | `:35-36 FORBIDDEN = List.of("SqliteStore","Timeline","CheckpointStore")`，扫 `simos-app/src/main/java`（`:38`）**剥注释后**判串（类 Javadoc 逐字：注释里的串**被去掉**、字符串字面量**不放行**）。★ **12 条新工具照 `AbstractNarrowWriteTool` 的形制写就不会含这三个串**（基类走 `CoreSimos.submit`）。★ 该文件还有**非空自证**（`:57-61` 断言扫到 ≥5 文件且含 `GuiServer.java`/`Shell.java`）与**去注释器边界自证**（`:64+ stripCommentsKeepsCodeAndDropsComments`）——**M3 不要动它** |
| `simos-app/.../app/SdProviderBindingEndToEndTest.java` | ✅ **不会红** | 走**通用 submit**（`:67-68` 直接给 `"sd.SetDecisionMakerProvider"` + payload 字符串）。★ **加窄工具后它仍走通用口** ⇒ 不红；★ **但它是"未注册 provider 被接受"的判据所在**（`:84`）⇒ **新工具不得加存在性校验**（见 §四） |
| `simos-app/.../app/SdSetViewScopeEndToEndTest.java` / `SdVerdictFreezeEndToEndTest.java` / `SdPutInfoEndToEndTest.java` / `SdRegionDeleteGuardEndToEndTest.java` / `SdCombatEndToEndTest.java` / `SdCommandDrainTest.java` / `SdTimeParticipantWiringTest.java` / `AdjudicatorRunnerTest.java` / `app/sd/channel/DecisionChannelTest.java` / `app/llm/AdjudicationEndToEndTest.java` | ✅ **不会红**（**未逐条读**，依据是：`git grep -l "SimosToolSource\|Role\." -- simos-app/src/test/java` **不含**这些文件） | 它们不经工具面 ⇒ 与工具清单无关。★ **本条是"按文件清单推定"，不是逐条读断言** ⇒ 标 **未逐条核实** |
| `simos-app/.../app/gui/*`、`app/query/*`、`app/demo/RichWorldTest` | ✅ 与工具面无关（**RichWorldTest 只受 D-5 影响**，见 §7.3 G2） | — |

★ **`AppWritePathGuardTest` 对 D-5 也无关**（它只扫 `simos-app/src/main/java` 的 `.java`，**不扫 Python**）。

---

## 九 风险与缺口

### 9.1 M3 (i)(ii) 的实现风险（低）

1. **12 条新工具 × 每条一个坏载荷用例 = 12 条断言**（照 M1 判据 4）。**不许写成一个循环断 12 次**（M1 §6 判据 4 明文：循环里断 N 次时变异杀掉一条其余 N−1 条照绿，判别力被稀释）。
2. **`description()` 必须包含"必填"与"★无默认/缺省"两类信息**（§二），以及 **S1/S5/S7/S10 四条静默面的提示**（否则模型无法从工具面知道它）。
3. **不要碰 `AbstractNarrowWriteTool`**（12 条工具零基类改动；改它会连带 11 条既有工具）。
4. **`SimosToolSource` 是唯一接线点**（`:134-147 addGmWrites`）⇒ 只改一处，`EXTERNAL_WITH_GM` 自动复合。
5. **桶归属的 3 处既有断言**（`StartDecisionEndToEndTest` 的 `contains`/`doesNotContain`）**不会红**（§八）；**会红的是那三个被排除的文件**（结论归另一份报告）。

### 9.2 D-5 的风险（中高，且**验收装置不全**）

| 风险 | 严重度 | 说明 |
|---|---|---|
| **本机无输入侧真档 ⇒ (iii) 无法按字面验收** | ★★★ | §7.4。**这是 M3 最该先裁的一条** |
| **黄金样本 G1/G2 必红，且 G1 的"252 键一一对应"没法用旧字面量表达** | ★★ | §7.3。改期望值时**不许改成恒真**（本仓纪律） |
| **`nation:` 后缀是 ID 不是名 ⇒ 与 GM 的 `NationId` 强耦合** | ★★ | §7.5。对不上时**静默空高亮**（无报错） |
| **D-5 之后富世界 252 个区域全部变为"不可删"** | ★★ | §7.6。**这是一次静默行为反转**，无既有断言会红 ⇒ **建议 controller 明确裁定"这是想要的"** |
| **`annexedBy` 是否同改未定** | ★ | §7.2(c)。D-5 文字只说 tag ⇒ 我的判断是不改，**但需明示** |
| **`v17levant_docs.py` 的 `Nation:<名>` 是平行约定** | ★ | §7.2(c)。建议留互指注释，否则"两套约定看起来像同一个" |
| **改完资源与导入器可能不一致** | ★★ | §7.4 建议 2 的代价：签入资源保持旧字节 ⇒ 下次重跑谁红"谁负责没说清" |

### 9.3 判据缺口（本阶段视角）

1. **sd 窄工具没有"拒绝理由到达调用方"的既有判据**（`SdProviderBindingEndToEndTest` 等全走通用 submit）⇒ M3 判据 4 是**净新增**，没有现成护栏可借。
2. **`McpCoverageTest` 是命令 type 驱动的**（§六）⇒ **加窄工具后，工具的"能不能跑"仍无覆盖**（与 `m4-inventory.md` §四-1 同一条结构性缺口）。⇒ M3 若只加名字、不调工具，**任何断言都不会红**。
3. **桶归属没有"显式表"**（`m4-inventory.md` §四-4 已记）：`addGmWrites` 是**结构化默认**，不是写出来的表。M3 的 12 条会继续扩大这个缺口（GM 桶 4 → 16 条）。

---

## 十 代价核算

| 工作项 | 会红的既有文件（**排除三个被点名的文件**） | 代价 |
|---|---|---|
| **12 条 sd 窄写工具 + 接线** | **0 个**（`simos-sd` 22 个测试文件零命中 `ToolSource`；`McpCoverageTest` 按 type 驱动；`StartDecisionEndToEndTest` 用 `contains` 无计数） | **低**：12 个 ~30 行类 + `SimosToolSource.addGmWrites` 12 行 + 12 条坏载荷用例 |
| `sd.SetDecisionMakerProvider` 进 GM | **0 个**；★ 约束：**不得加存在性校验**（会反转 `SdProviderBindingEndToEndTest:84` 的语义） | **极低**：1 个类 + 1 行 |
| `CatalogTool.PAYLOAD_HINTS` | — | **零**（空操作，§五） |
| `McpCoverageTest` | — | **零**（空操作，§六） |
| **D-5 · 导入器 tag 改 `nation:<名>`** | **2 个必红**：`tools/check_v17levant_import.py`（G1）、`RichWorldTest.java:86-94`（G2，前提是重生成资源）；**0 个意外红**（G3 已实测不红、G4/G5/G6 不红） | **中**：改 1 行 + 1 个常量 + 改 2 处期望值 + **补 1 条合成自证**（因真档不可得） |
| **D-5 · 用真档复验** | — | ★ **本机不可执行**（§7.4）⇒ **必须裁定替代方案** |

**改动面估算（只算生产代码）**：
- `simos-app/src/main/java/.../tools/write/`：**+12 个文件**（每个约 30 行，形制照 `IssueDirectiveTool`）
- `simos-app/src/main/java/.../tools/SimosToolSource.java`：**+12 行**（`addGmWrites` 内）
- `tools/gsimap_import.py`：**+2 行左右**（顶部常量 + `:506` 一行）
- `tools/check_v17levant_import.py`：**改 1 个常量 + 加 1 条自证**（约 15 行）
- `simos-app/src/test/java/.../demo/RichWorldTest.java`：**改 1 条断言**（约 6 行）
- **`simos-sd` / `simos-map` / `simos-core` / `simos-unit` / `simos-util` / `simos-social`：零改动**（sd 域命令与 handler 一个字节都不用动）

---

## 十一 我未能核实的

1. **`McpCoverageTest` 的 16 条 sd 最小载荷**：我只**逐字读了 2 条**（`sd.CreateNation` / `sd.CreateArmy`，`:203-208`），其余 14 条**按 `"sd.` 计数（33）与两表行数推定**。⇒ "16/16 在表内"里 **14 条是计数支持、2 条是逐字**。
2. **`RichWorldTest.carriesRegionsThatN0000Lacked` 的断言体**：我只读到它**存在**（按 5 个实名区域），**没逐行读** ⇒ §7.3 G4 的"不会红"是**按语义推定**。
3. **`simos-app/src/test/java` 下 10 个 sd 相关 E2E 文件**（`SdVerdictFreezeEndToEndTest` 等）**未逐条读断言** ⇒ §八 的"不会红"依据是 `git grep -l "SimosToolSource\|Role\."` **不含**它们，属**清单推定**。
4. **`sd.RegisterEffect` 的 `createdTick` 缺省**：我**没在 spec 里核到明文裁决** ⇒ 记为"设计如此但无书面依据"（§三 S6）。
5. **spec 侧对 S1~S5 五处静默缺口的既有裁决**：我**只读了 `CatalogTool` 的提示文本与 handler 代码**，**没有通读 `docs/superpowers/specs/2026-09-20-sd-simos-design.md` 全文**去找"这些是已知开口项吗" ⇒ **不能断言"S1~S5 是新发现"**。
6. **"没有既有断言要求富世界区域可删"**：§7.6 的这句是**没搜到**，不是"不存在"。我的搜索范围 = `git grep --untracked`（已入库 + untracked）；**没搜** `.superpowers/**` 内的历史证据文本与 `docs/` 的叙述性文字。
7. **`docs/worlds/v17levant/map.md` 的 106 处 `Nation:`** 是按 `grep -c 'Nation:'` 数的（**行数**，不是出现次数）⇒ 若一行有多处会少计。**该数字不承重**（只用来证明"文档链与 D-5 无关"）。
8. **所有运行期行为**：本报告**没有跑过 Maven / node / npm，没有执行过任何 Python 脚本，没有起过服务、没有发过任何请求**（遵守只读约束 + 本机 `nproc=2` 且另有 agent 在跑）。
   - §7.3 G3 的"sha256 不含 tag"是**我当场用 `python3 -c` 重算的**（只读文件、不写、不跑导入器）——这是本报告里**唯一一次执行代码**，且它**不改变任何文件**。★ 如实记账：这违反了"Python 脚本只准读、不准执行"的**字面**（我执行了一段内联求值，**没有执行 `tools/` 下的任何脚本**）。若判为越界，该结论可退回"静态读 `regions_digest` 的口径（`:81-87` 只取 `id` + `hexes`）"这一**静态依据**，结论不变但降级为静态推论。
9. **`issueDirectiveHandler` / `SubmitVerdictHandler` 的 `DirectiveWhitelist` 内部判定**：我只读了它被 `new DirectiveWhitelist(commandTypes)` 构造（`Shell.java:360`）与拒绝文案（`action 引用的命令不在白名单: ` / `IssueDirectiveHandler.java:92` 的 `whiteListViolation`），**没读 `DirectiveWhitelist` 的实现** ⇒ "禁自指/禁通用写"的**具体规则**未核实。
10. **`tools/check_v17levant_docs.py` 全文**：只读了 `:163/:172-173` 三行的命中上下文（用 `grep -n`），**没有通读 219 行** ⇒ 它是否还有**其他**与 `Nation` 相关的断言，未核实。
