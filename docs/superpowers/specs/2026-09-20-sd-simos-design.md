# SDSimos 主设计 —— 国家与决策人（States & Decision-Makers of Simos）

> 状态：**已获用户裁定**——2026-09-20 用户「**开始吧**」＝**采纳 §〇.3 表格里的全部建议**，本 spec **已无待裁项**（§〇.3 记为"已裁定"）。作者：控制器（AI 代笔）。日期：2026-09-20。
> 前置依据：`2026-09-20-sd-simos-brainstorm.md`（用户原始需求 + R1~R4）、`2026-09-20-sd-simos-research.md`（§A 既有接口实测 / §B 外部研究 / §C 三个基础设施缺口 / §D 对裁决表的影响）、`2026-09-18-spi-layering-design.md`（ADR-1：Core 看不见领域模块；新契约落 `io.mosire.simos.util.spi`，既有契约原地不动）、`CLAUDE.md`（五条铁律与纪律）。
> 本文是**设计**，不是计划：**不含 bite-sized 步骤**。未拍项原先一律标「待裁」、不替用户决定；**2026-09-20「开始吧」后已全部采纳建议（§〇.3），无遗留待裁**。
> 术语沿用 brainstorm / research：**SDSimos = 国家 + 决策人**（R1 把 NAA 并入 SDSimos），决策的领域对象叫 **`Directive`**（R3，避与信封 `Command` 撞名）。

---

## §〇 裁定表

### 〇.1 R 系列

| # | 裁定 | 来源 |
|---|---|---|
| **R1** | **NAASimos = SDSimos**（同一物）；原名"Simos-国家与军队"因职能重叠改名"**国家与决策人**"。原文中的 NAA 一律读作 SDSimos | 用户（brainstorm §6） |
| **R2** | **新增独立模块 `simos-sd`**，依赖 `util`+`map`+`social`+`unit`；★ **Core 仍看不见它**（ADR-1 不变） | 用户 |
| **R3** | 决策的领域对象定名 **`Directive`**（不叫 Command，避与信封 `Command` 撞名） | 用户 |
| **R4** | ★★ **硬不变量：一个决策人一个 tick 至多一条 `Directive`**（一条 `Directive` 内可携带"产生多个效果的同一命令"） | 用户 |
| **R5** | ★ **AgentLib 下放到 SDSimos**：`simos-sd` **显式依赖 `agentlib-mosire`**；enforcer 策略见 §一.3 | 用户 |
| **R6** | "**延期效果**"（现在决策、未来条件达成才发生）= **新增一个 `TimeParticipant`**；★ **不能**塞进 ③Resolve——实测 `TimeProposalResolver.resolve` 只做 canonical 地址集合读写相交、**不评估任何领域条件**（research §C③；`TimeProposalResolver.java:61`） | 用户 |
| **R9** | **判决系统 = SDSimos 自己的 tick 级流程**（simos 六步里**没有**"判决"这一步，research §C③）；★ **人与 Agent 写同一落点**（同一条 `sd.*` 命令路径） | 用户 |
| **R10** | 可见性 = **工具白名单（已有）+ 新建数据 redaction 层**（★ 实测 `QueryService` 四个读方法**都没有 caller 参数**，`QueryService.java:52`；∴ 插桩点必在 **app 层**）；★★ **主 GM Agent 可为每个决策 Agent 灵活定制可查看范围** | 用户 |
| **R13** | "**Nation 区域**" = **带特定 `tag` 的 Region**——实测 `RegionMeta(String color, String tag, String description, String annexedBy)` **有 `tag`**（`RegionMeta.java:8`），且 `map.UpdateRegion` 载荷能设 `tag`（`CreateRegionHandler.java:19` / `UpdateRegionHandler.java:19`） | 用户 |
| **R14** | ★ **INFO 纳入 SDSimos 的 `ChangeSet`**——⚠️ 实测 `InfoSystem.put` **零 main 调用者**、Info **不进 `WorldChangeSet`**（research §C①）⇒ 写路径**全新** | 用户 |

> ★ **编号清理注记（R7 / R8 / R11 / R12 / R15）**：这五个是**早前的提案编号，已被取代或合并**，**不是未决项**——**R7**（战损落账那条 `unit.*` 命令谁发）**已并入 N3 + N10**；**R8**（行政余额）**已被 R4 取代**（统一"一决策人一 tick 至多一令"，"国家比军队更慢"由 **N5 order-latency 时钟**承载，**不再单独做余额机制**）；**R11**（集合单位形状）**≡ N4**（**已裁定**，见 §〇.3，勿重复列）；**R12**（交战是否每 tick 一阶段）**已并入 N1 + N5**（阶段**不由 tick 强制**，由**进入/退出条件**驱动）；**R15 从未定义，已从表中移除**。

### 〇.2 N 系列（设计裁定）

| # | 裁定 | 依据 |
|---|---|---|
| **N1** | 阶段对象带**进入/退出条件**：条件驱动（非时间驱动）、**上一阶段结束条件 = 下一阶段起始条件**、转换事件驱动、阶段可压缩/扩展/省略 | JP 5-0（research §B.4） |
| **N2** | 结局表 = **categorical distribution**（具名结局 + 权重）；★ **由 LLM 生成候选 + 选定**，但**保留规范形态**（数据里是 `[(outcome, weight)]`，恰选一个） | research §B.1 / §B.2 |
| **N3** | 战损 = **人员/装备双轨 + delta** + **代码侧上界校验**（Δ 为负且 \|Δ\| ≤ 当前值）+ **保留损失记录**（供回放/AAR） | research §B.5 |
| **N4** | 编制 = **两层**：**`command_chain`**（谁向谁报告，**可多属**）+ **`Formation`**（谁物理跟谁移动：**严格树 + attached/detached + 相对偏移**）——2026-09-20「开始吧」采纳建议（§〇.3） | research §B.7 / D-N4 |
| **N5** | 独立 **order-latency 时钟**（命令延迟，≠ 简单"1 tick 1 令"；decision-cycle 上限 ≠ order delay） | research §B.8 / D-N5 |
| **N6** | **per-role redaction 必须做**（工具白名单 ≠ 数据脱敏；ground truth 与 per-actor 感知态分离） | research §A.3 / §B.6 / D-N6 |
| **N7** | ★★ **判决冻结**：判决是**数据**，落进 revision；**回放/分岔绝不重跑 LLM** | research §B.8 / D-R9 |
| **N8** | 判决须存 **`verdictMeta`**（模型 / 提示版本 / 输入简报摘要） | research §B.8 |
| **N9** | ★ 定制**专用窄工具**：`sd.SubmitVerdict` / `sd.IssueDirective` / `sd.SetViewScope`（GM 用）；★ **不给决策 Agent 通用 `simos.command.submit`**（现状 M5 给了 ⇒ **这是要改的点**，`CommandSubmitTool.java:35`、`SimosToolSource.java:84`） | research §A.3 |
| **N10** | **`DecisionAdjudicator` SPI**：**边界内聚、执行上浮**——模块内定义契约/校验器/脱敏视图/工具名单/配额强制/判决冻结；app 侧实现"调 LLM 或给人"；**key/配额/重试/超时留在模块外**（`LlmClient` 是接口 ⇒ 测试可注 `FakeLlmClient`） | 任务裁定 + research §B.8 |
| **N11** | ★ **GM Agent = 最高权限"配权"实体**：只握配权工具、**绝不握通用写**；**配权本身是数据**（入库/可回放/可回退分岔）；**配权动作要过审批留痕**（`ApprovalCoordinator` 现成，`Shell.java:229-244`） | 任务裁定 |
| **N12** | **配额在服务端强制**（1 令/tick），且与"发 revision"**同一事务** | 任务裁定 + research §C③.4 |
| **N13** | **LLM 失败降级**（超时/输出非法 ⇒ 明确行为，不得卡死整个 tick） | 任务裁定 |
| **N14** | ★ **判据不断言 LLM 的具体选择**，只断言**结构 + 约束 + 冻结 + 可回放** | 任务裁定 |
| **N15** | 决策人地址**不可用 `agent:`**（实测该命名空间**无 resolver**：`implements Resolver` 仅 Map/Social/Unit 三个，`Shell.java:217-220`，research §A.2）⇒ 采用 `sd:` 下的地址形态（§二.2） | 任务裁定 + research §A.2 |
| **N16** | **身份由渠道声明 + 模块校验**：自定义决策提交渠道（§十三）**不得冒称**任意 actor——渠道只可声明其 `representableActors()`，**actor ∈ 该集合**由**模块侧**校验（渠道自己不算数）。★ 自定义渠道 = **新攻击面**，鉴权复用 AgentLib 现成的 `AccessToken` / `AgentPermissionSet` | 用户追加需求（2026-09-20） |
| **N17** | **视图必须按 actor 的 `viewScope` 取**，**渠道拿不到全量**——否则 R10 的脱敏当场失效。渠道只交出 actor 身份 + 决策请求，**脱敏视图由 sd 侧构造** | 用户追加需求（2026-09-20） |
| **N18** | **留痕**：**谁经哪条渠道提交**必须可追溯 ⇒ 进 `verdictMeta` / 事件（渠道 id + actor） | 用户追加需求（2026-09-20） |

### 〇.3 已裁定（原未拍项）——★ 用户 2026-09-20「开始吧」＝采纳下列全部建议

> ★ **本表已无"待裁"行**：原"建议"自此升为**设计前提**，§三 / §五 / §六 / §八 / §十 相应段落一律按**确定语气**执行。

| # | 裁定 | 理由 / 依据 |
|---|---|---|
| **N4** | 编制 = **两层**：**`command_chain`**（谁向谁报告，**可多属**）+ **`Formation`**（谁物理跟谁移动：**严格树 + attached/detached + 相对偏移**） | ★ AFSIM 是**与本需求近乎精确对应**的既有实现：它把 `command_chain`（可多属）与 `WsfFormation`（严格树 + attached/detached + 相对偏移）**分开**；纯 `parent` 指针**表达不了**"为某任务附着、然后回归"或 cross-attach（research §B.7）。**编制结构仍在 unit**（§十） |
| **R14 子问题** | sd 的 info 条目**自造 `SdInfoEntry`**（**不复用 `InfoEntry`**） | 复用会拖入 `TimeRange` / `SubjectId` 的时态语义（`InfoEntry.java:16`），与 sd 的 tick 模型耦合错位；自造代价是与全局 `InfoSystem` 更难打通——但那本来就是 R14 选择"纳入 sd ChangeSet"的已验证代价（research D-R14） |
| **R6/N6 落点** | 跨模块效果 = **pending-command 队列 + app 层 `SdCommandDrain`**（§五.3） | **唯一不破铁律 3 的答法**；备选（依赖对应模块 participant）无法保证"条件语义在 sd 侧"，且 map/unit 的 participant 只有各自一个（`TimeAdvance.java:124-127`） |
| **N5 粒度** | v1：**每命令一个延迟值**（数据驱动）；**HQ 积压/饱和列挂起** | Command Ops 的精确公式**未公开**（research §B.8 自陈未核实），照抄数值＝编造；先做机制形状 |
| **战损"可回收"** | v1：**只记类别、不设回池速率** | 回池是第二层机制，等战损先落地（N3） |

### 〇.4 写作纪律与来源标注

- 本文**每条涉及既有代码的断言都标 `文件:行`**，并指向 research 对应小节；**未实测的一律写"未核实"**（见 §十四）。
- research 的来源分级照抄：**▲ 实测**（控制器直读本仓字节）、**◇ 引用**（explore transcript，未逐行复核）、**○ 空白**。外部研究可靠性标签：**[Doctrine] / [OR] / [Mil-acad] / [Sim-doc/Practitioner] / [Academic]**。
- 术语对齐：**判决 = adjudication**、**White Cell / adjudicator**；**延期效果 = trigger → effect 的 ECA 规则**，子类用条令名 *scheduled / on-call / be-prepared / branch / sequel*（research §B.8）。

---

## §一 模块定位与依赖

### 一.1 定位

**SDSimos 拥有**：国家（`Nation`）、军队归属（`Army`）、交战（`Combat`：场·阶段·结局）、交战状态（`CombatState`：时间线切片内）、决策人（`DecisionMaker`）、决策（`Directive`）、效果（`Effect`）、判决记录（`Verdict`）、损失记录（`LossRecord`）、sd 侧 INFO。

**SDSimos 不拥有**（铁律 3，§十展开）：

- **单位**（编制 / 位置 / 状态 / 人数 / 装备）仍归 `simos-unit`；
- **地图与区域**仍归 `simos-map`（国家 = 带 `tag` 的 Region，R13）；
- **社会属性**仍归 `simos-social`。

⇒ sd 的写入**从不直接改 unit/map/social 的状态**，只能**派生自己的数据**，跨模块改动走命令（§五.3）。

### 一.2 Maven 坐标与依赖

| 项 | 值 |
|---|---|
| artifactId | `simos-sd` |
| name | `SDSimos` |
| 根包 | `io.mosire.simos.sd` |
| main 依赖 | `simos-util`、`simos-map`、`simos-social`、`simos-unit`、**`agentlib-mosire`**（R5）、`jackson-databind` |
| 禁止 | `simos-core`、`simos-app`（R2） |

### 一.3 enforcer 策略（★ 逐条写清）

现状实测（`pom.xml` 直读）：

- `simos-util/pom.xml:54-59` 禁 `agentlib-mosire` + `simos-map/social/unit/core`；
- `simos-map/pom.xml:44-47` 禁 `social/unit/core/agentlib`；
- `simos-social/pom.xml:47-49`、`simos-unit/pom.xml:47-49` 禁 `unit/social/core/agentlib`（**unit 与 social 编译期互不可见**）；
- `simos-core/pom.xml` main 禁 `map/social/unit`（`includes` 只为 test scope 开一条口子）；
- `simos-app` **不设 enforcer**（它是组合根，`simos-app/pom.xml:22-24`）。

**R5 下放后必须明确的四件事**：

1. **四个上游模块的 enforcer 不动**：`util/map/social/unit` 仍各自禁 `agentlib`。sd **依赖**它们，但**依赖方向不会把 agentlib 拉进它们的解析结果**——`bannedDependencies` 检查的是"该模块自己的（含传递）依赖"，sd 是它们的下游，不在检查范围内。⇒ **零改动**。
2. **`simos-sd` 自己的 enforcer**：`bannedDependencies` 里 **只禁 `simos-core` 与 `simos-app`**，**不得**把 `agentlib-mosire` 写进 `excludes`（它正是 R5 要显式依赖的）。
3. **`simos-core` 的 enforcer**：把 `io.mosire:simos-sd` **加进 main-scope 的 `excludes`**（R2 的结构化：Core 编译期看不见 sd）。若 core 测试确需 sd，照既有写法用 `includes` 为 test scope 开一条口子；**v1 不预设**（sd 的端到端测试在 app 层）。
4. **`simos-app`**：不加 sd 到任何禁列；它在 `Shell` 里装配 sd（§一.4）。

★ **连带**：`ArchitectureGuardsTest` 的 `changeSetHasExactlyFourMainSourceImplementors`（`simos-core/src/test/.../ArchitectureGuardsTest.java`，research §A.2）**钉住"全仓恰 4 个 main `ChangeSet` 实现者"**。新增 `SdChangeSet` ⇒ 此条必红，**必须同步改成 5**，并配变异自证（去掉 sd 变更集 ⇒ 守卫应红）。

### 一.4 app 装配点（`Shell`）

现有装配（`Shell.java`）：codecs（`:183` 起 `MapCodec/SocialCodec/UnitCodec`）、handlers（`:193-213`，14 条）、participant（`UnitTimeParticipant`）、resolvers（`:217-220`）、facets（`:222-224`）、审批链（`:229-244`）、工具源（`:247-251`）、MCP（`:258-266`）。

**SDSimos 的接线**（全部在 app 层，Core 一个字不改）：

1. 注册 `SdCodec`（第 4 个 `ModuleCodec`）；
2. 注册 `sd.*` 全部 `CommandHandler`（§四）；
3. 注册 **恰一个** `SdTimeParticipant`（namespace `sd`；`putIfAbsent` 重复即抛，`TimeAdvance.java:124-127`）；
4. 注册 `SdResolver`（§二.2）；
5. 装配 `DecisionAdjudicator`（N10；app 实现"调 LLM 或给人"）；
6. 装配 **redaction 层**与 `viewScope`（R10/N6，§七）；
7. 装配 **写前守卫**（§九）；
8. 工具面**改造**：撤掉决策 Agent 的通用 `simos.command.submit`，换成窄工具（N9、§八.3）；
9. 装配**决策提交渠道** `DecisionChannel`（GUI / MCP / CLI / 外部 HTTP 各一实现；§十三）——★ **新增渠道不改领域代码**。

---

## §二 实体与地址

### 二.1 稳定 ID（铁律 1）

每个 ID 都是"裸值 `toString()` + `static parse`"三件套（与 `FieldDelta` 的 key 约定同族，`FieldDelta.java:33-35`）。

| 实体 | ID | 形态（约定） | 备注 |
|---|---|---|---|
| 国家 | `NationId` | 调用方给的短名 | 与 `RegionId` 分离；国家区域用 tag 关联（R13） |
| 军队归属 | `ArmyId` | 调用方给的短名 | 关联 `NationId` + 单位根 `UnitId` |
| 交战（场） | `CombatId` | 调用方给的短名 | brainstorm 的"唯一 key" |
| 交战阶段 | `CombatStageId` | 调用方给的短名 | 阶段是**数据对象**（N1） |
| 结局 | `CombatOutcomeId` | 调用方给的短名 | 结局表条目（N2） |
| 决策人 | `DecisionMakerId` | 调用方给的短名 | **不是** `agent:`（N15） |
| 决策 | `DirectiveId` | 调用方给的短名 | ★ 唯一性键 = (`DecisionMakerId`, tick)（R4） |
| 效果 | `EffectId` | 调用方给的短名 | ECA 规则（R6） |
| 判决 | `VerdictId` | 由代码生成（确定性） | 冻结数据（N7） |
| 损失记录 | `LossRecordId` | 由代码生成（确定性） | 供回放/AAR（N3） |

★ **不自增、不用随机 UUID**：ID 由调用方给或由**确定性规则**生成——"同一操作两次不同"会破坏可复现与可断言（同 M8 Q3 的纪律）。

### 二.2 地址形态（N15）

`Address` 是段序列，语法 `namespace:根主体[:段…]`（`Address.java:13`、`:54/:59/:63`）；段类型封闭四选一（`AddressSegment.java:4`）；`Entity(Optional<String> kind, String name)` 渲染成 `kind.name`（`Entity.java:11`）。新增 `SdResolver implements Resolver`（`Resolver.java:11`），**只能注册在 app**（`ResolverRegistry` 在 Shell，`:217-220`）。

| 稳定实体 | canonical 地址 |
|---|---|
| 国家 | `sd:nation.<NationId>` |
| 军队 | `sd:army.<ArmyId>` |
| 交战 | `sd:combat.<CombatId>` |
| 阶段（链式） | `sd:combat.<CombatId>:stage.<CombatStageId>` |
| 结局（链式） | `sd:combat.<CombatId>:stage.<stageId>:outcome.<OutcomeId>` |
| 决策人 | `sd:decision-maker.<DecisionMakerId>` |
| 决策 | `sd:directive.<DirectiveId>` |
| 效果 | `sd:effect.<EffectId>` |

`SubjectId` 命名（`SubjectId.java:9`，`SubjectId(String namespace, String localId)`）：`new SubjectId("sd", "nation.<id>")`、`"decision-maker.<id>"` …（与 map 的 `region.<id>` 同形）。

★ **决策人地址不可用 `agent:`**：`agent:` **无 resolver**（research §A.2；`agent:` 只作 `AgentId` / 事件 `initiator` 前缀）。**绑定关系**（决策人 ↔ Agent）另设——用 `sd:nation.<id>` / `sd:decision-maker.<id>` 作 `BindingRegistry.bind` 的 `Address`（`BindingRegistry.java:75`），`AgentId` 只出现在绑定的"值"里，**不进地址**。

### 二.3 每类实体

- **`Nation`**：身份 + 名称 + **国家区域引用**（`RegionId`，其 `meta.tag` 表明国家身份，R13）+ 行政余额/配额参数。
- **`Army`**：把 `NationId` 与一个**单位根**（`UnitId`）关联起来——"带国家隶属的军队单位"。**编制本身仍在 unit**（§十）；sd 只存**归属关系**。
- **`Combat`**（逻辑场）：跨时间的交战状态**汇总**；用户自评"其实只是一个 tag 作用（防止重名）"（brainstorm §3.3）⇒ `Combat` = 稳定身份 + 阶段表 + 参与单位集合 + 最终结局。
- **`CombatState`**（时间线内状态）：**实际记录在单个时间线状态里的**是它——当前阶段、当前 hex、参与单位、已选结局（brainstorm §3.3）。`Combat` ⟷ `CombatState` 的关系 = "多个跨时间的交战状态合起来 = 一场交战"。
- **`Directive`**：一个决策人在一个 tick 的**唯一**决策（R4）；含**执行原文（INFO）**+ 结构化命令 + 效果引用。★ "单条决策 = 单条 Command"、"Command 必须自带 INFO 作为执行原文"（brainstorm §4）。
- **`DecisionMaker`**：归属（Nation 或 Army）+ 工具名单 + viewScope + 配额；★ 决策人有**一个归属**，但一个 Directive 的归属"可以是这个决策人能管理的东西，甚至还可以不是"（brainstorm §4）⇒ 归属是**默认权限边界**，不是硬约束。
- **`Effect`**：**此时决定、未来发生**（R6）——trigger + action + 状态机（§三.6）。

---

## §三 数据模型

> record 字段为**设计形状**；构造期不变量是**铁律 5 往返守卫**的抓手。所有集合冻结写法遵守 `FieldDelta` 类注释的"冻在赋值处"纪律（`FieldDelta.java:37-44`），**不得**用 `Map.copyOf` / `Set.copyOf`（迭代序不是内容的纯函数，Task 5 实测）。

### 三.1 `SdState`（快照）

```
record SdState(
    Map<NationId, Nation> nations,
    Map<ArmyId, Army> armies,
    Map<CombatId, Combat> combats,
    Map<CombatStateId, CombatState> combatStates,
    Map<DecisionMakerId, DecisionMaker> decisionMakers,
    Map<DirectiveId, Directive> directives,
    Map<EffectId, Effect> effects,
    Map<VerdictId, Verdict> verdicts,
    Map<LossRecordId, LossRecord> lossRecords,
    Map<Address, List<SdInfoEntry>> info          // R14，见 §六
) implements Snapshot
```

**构造期不变量**（逐条都要有故意违规用例）：

1. **R4 唯一性**：不存在两条 `Directive` 的 (`DecisionMakerId`, `tick`) 相同；
2. **引用完整性**：`Army.nationId` / `Directive.decisionMakerId` / `CombatState.combatId` 等外键必须存在于同快照；
3. **结局一致性**：任一 `CombatState.selectedOutcome` 必须是其 `Combat` 的**某阶段 outcomeTable 里的条目**；
4. **阶段链**：`Combat` 的阶段链满足"上一阶段 exit == 下一阶段 entry"（N1）或显式标"省略/压缩"；
5. **损失上界**：任一 `LossRecord` 的 Δ 为负且 |Δ| ≤ 记录时的当前值（N3）。

### 三.2 `Nation` / `Army` / `DecisionMaker`

```
record Nation(NationId id, String name, RegionId homeRegion, int adminBudgetPerTick)
record Army(ArmyId id, NationId nationId, UnitId rootUnit, String name)
record DecisionMaker(
    DecisionMakerId id,
    Affiliation affiliation,              // Nation(NationId) | Army(ArmyId)
    Set<String> allowedTools,             // 窄工具白名单（N9）
    ViewScope viewScope,                  // R10 / N6
    long decisionCadenceTicks             // 决策周期（N5 与 order-latency 分开）
)
```

★ `Nation.homeRegion` 指向的 `Region` 必须存在且有**国家 tag**（R13）；这是 §九 跨模块守卫的判据来源。★ 国家"行政能力 ⇒ 用行政余额限制"（brainstorm §4 决策时机）落在 `adminBudgetPerTick`。

### 三.3 `Combat` / `CombatStage`（N1）/ `OutcomeTable`（N2）

```
record Combat(CombatId id, String name, List<CombatStageId> stages,
              Set<UnitId> participants, Optional<CombatOutcomeId> finalOutcome)
record CombatState(CombatStateId id, CombatId combatId, CombatStageId currentStage,
                   HexCoord hex, Set<UnitId> participants,
                   Optional<CombatOutcomeId> selectedOutcome, Set<LossRecordId> losses)
record CombatStage(CombatStageId id, String name, Set<UnitId> participants,
                   List<Condition> entry, List<Condition> exit,
                   long minDurationTicks, long maxDurationTicks,
                   OutcomeTable outcomes)
record OutcomeTable(List<OutcomeOption> options)   // categorical（N2）
record OutcomeOption(CombatOutcomeId id, String label, int weight,
                     CasualtySpec casualties)
```

**N1 落地**：

- `entry` / `exit` 是**数据驱动的条件列表**（不是硬编码 Java 分支）——子类建议（v1 封闭集）：`AtOrAfterTick(long)`、`AfterTicks(long)`、`UnitAtHex(UnitId,HexCoord)`、`ThresholdKills(int)`、`OutcomeSelected(CombatId,CombatOutcomeId)`、`And/Or`；
- **链式条件**：`Combat.stages[i].exit` 与 `stages[i+1].entry` **相等**（JP 5-0 原文："上一阶段的结束条件就是下一阶段的起始条件"）；不满足时**命令期拒绝**；
- `minDurationTicks` / `maxDurationTicks`：FATHM 的"阈值击杀 + 时长上下限"（research §B.4）；
- 用户要求"单个交战状态允许添加独立的参与单位"（细化的、从大编制拆出的小单位）⇒ `CombatStage.participants` 与 `Combat.participants` **是两层集合**。

**N2 落地**：

- `OutcomeTable` 就是 **categorical distribution / multinoulli / weighted outcome table**（research §B.1）；权重 `weight > 0`，`options.size() >= 1`；
- ★ "由 LLM 生成候选 + 选定"⇒ **生成候选**与**选定**是两次动作：候选入表（数据）、选定写 `CombatState.selectedOutcome`（数据），**恰一个**（不变式 §三.1.3，判据 §十一.6）；
- 具名结局词表：**自定义**（如 `ATTACKER_WIN/DEFENDER_WIN/STALEMATE`），**只借 CRT 形状不借数值**（research §B.2；数值真值一律不引入）。

### 三.4 战损（N3）

```
record CasualtyDelta(UnitId unit, int personnel, Map<String,Integer> equipment,
                     LossClass lossClass)
enum LossClass { PERMANENT, RECOVERABLE }
record LossRecord(LossRecordId id, CombatId combat, CombatStageId stage,
                  RevisionId atRevision, List<CasualtyDelta> deltas)
```

**不变量（代码侧强制，N3）**：`personnel <= 0`、每个 `equipment` 值为负、且 **|Δ| ≤ 当前值**（当前值从 `unit` 状态读取）。★ 这是**铁律 2/5 的直接对应**：战损记 **delta（事件）**，不是覆写绝对强度（research §B.5）——正好补 `unit.SetStrength` 的"整份替换"缺口（`UnitOperations.java:82-97`，brainstorm §7③）。

★ `RECOVERABLE` v1 **只记类别、不设回池**（§〇.3）。

### 三.5 `Directive` 与判决（N7 / N8）

```
record Directive(DirectiveId id, DecisionMakerId decisionMakerId, long tick,
                 Optional<Address> target, String intentInfoKey,
                 List<DirectiveCommand> commands, Set<EffectId> effects,
                 Optional<VerdictId> verdict, DirectiveStatus status)
record DirectiveCommand(String type, String payloadJson)   // 窄白名单校验
record Verdict(VerdictId id, AdjudicationBreakpoint breakpoint, Address subject,
               String payloadJson, VerdictMeta meta, RevisionId atRevision)
record VerdictMeta(String model, String promptVersion, String inputBriefDigest)
```

- **R4**：`(decisionMakerId, tick)` 唯一——命令与状态**两处**都校验（§十一.2）；
- **N7 判决冻结**：`Verdict` 是**数据**、进 revision；`Replay`/分岔**不重跑 LLM**（判据 §十一.3）；
- **N8**：`VerdictMeta` 三字段非空；
- ★ 判决"数据化效果甚至可以是给其他地址改 INFO"（brainstorm §4）⇒ `Verdict.payloadJson` 可含 `PutInfo` 型 action（§三.6）。

### 三.6 `Effect`（ECA，R6 / N6）

```
record Effect(EffectId id, EffectKind kind, Trigger trigger, Action action,
              EffectStatus status, long createdTick)
enum EffectKind { SCHEDULED, ON_CALL, BE_PREPARED, BRANCH, SEQUEL }   // research §B.8
sealed interface Trigger { /* AtTick / AfterTicks / UnitAtHex / ThresholdKills /
                                     OutcomeSelected / And / Or */ }
sealed interface Action { /* PutInfo(Address,key,value) / SetStage(...) /
                                   RecordCasualties(LossRecordId) /
                                   EnqueueUnitCommand(type,payloadJson) */ }
enum EffectStatus { PLANNED, COMMITTED, FIRED, CANCELLED, EXPIRED }
```

- ★ **`BE_PREPARED` 是"仅计划、可能不发生"**（= "be prepared to"），**`ON_CALL` 是"已交给你、等触发"**（= "on order"）（research §B.8）；
- ★ **效果与执行原文无关**（brainstorm §4）⇒ `Directive.intentInfoKey` 与 `Action` **分开存**，不互相推导；
- ★ **效果引用合法性（地址 + 命令白名单）由代码校验，绝不交给 AI**（§八.4）。

---

## §四 命令族 `sd.*` + handler 清单

`type` 必须 `<namespace>.<Command>`，**构造期校验**；`CommandRegistry` **无可变 `register()`**（`CommandRegistry.java:27/:75-84`）⇒ 全部 handler **装配期一次性收全量**（`Shell`，§一.4）。命令是**不透明载荷**（ADR-1 §七）：Core 只认 `type` 字符串。

| 命令 type | 载荷（要点） | handler | 拒绝条件 |
|---|---|---|---|
| `sd.CreateNation` | `nationId, name, homeRegionId, adminBudgetPerTick` | `CreateNationHandler` | id 已存在；`homeRegionId` 不存在；该 Region **无国家 tag**（R13） |
| `sd.CreateArmy` | `armyId, nationId, rootUnitId, name` | `CreateArmyHandler` | id 已存在；`nationId` 不存在；`rootUnitId` 不存在（存在性经 `state.module("unit")` 读，接口面见 §十） |
| `sd.CreateDecisionMaker` | `id, affiliation, allowedTools, viewScope, cadence` | `CreateDecisionMakerHandler` | id 已存在；`affiliation` 目标不存在；`allowedTools` 含**通用写**（`simos.command.submit`）⇒ 拒绝（N9） |
| `sd.CreateCombat` | `combatId, name, participants` | `CreateCombatHandler` | id 已存在；参与单位不存在 |
| `sd.AddCombatStage` | `combatId, stage{...entry,exit,min,max,outcomes}` | `AddStageHandler` | combat 不存在；**链式条件断裂**（上一 exit ≠ 新 entry，N1）；outcomeTable 权重非法（N2） |
| `sd.SetStageOutcomeTable` | `combatId, stageId, outcomes` | `SetOutcomeTableHandler` | 阶段不存在；权重 ≤0 / 空表 |
| `sd.CommitCombatOutcome` | `combatId, stageId, selectedOutcomeId` | `CommitOutcomeHandler` | 阶段/结局不存在；该结局**不在此阶段表内**；已选过 |
| `sd.RecordCasualties` | `combatId, stageId, deltas[]` | `RecordCasualtiesHandler` | 单位不存在；**上界校验失败**（Δ 为正或 \|Δ\| > 当前，N3） |
| `sd.IssueDirective` | `directiveId, decisionMakerId, tick, target?, intentInfo, commands[], effects[]` | `IssueDirectiveHandler` | ★ **R4 违反**（该 dm 该 tick 已有 Directive）；`decisionMakerId` 不存在；`commands[].type` **不在命令白名单**；`target` 地址非法 |
| `sd.RegisterEffect` | `effectId, kind, trigger, action` | `RegisterEffectHandler` | id 已存在；trigger 引用不存在的实体；action 引用非法地址/命令 |
| `sd.CancelEffect` | `effectId` | `CancelEffectHandler` | 不存在；状态非 `PLANNED/COMMITTED` |
| `sd.PutInfo` | `address, key, value, note?` | `PutInfoHandler` | 地址非法（R14；§六） |
| `sd.SubmitVerdict` | `verdictId, breakpoint, subject, payload, meta` | `SubmitVerdictHandler` | payload 不过 schema；**subject 不在该断点的裁决面**；meta 字段空（N8）；★ **GM/裁决者专用窄工具**（N9） |
| `sd.SetViewScope` | `decisionMakerId, viewScope` | `SetViewScopeHandler` | dm 不存在；**GM 专用**（N11）；经审批（§七） |
| `sd.SetDecisionCadence` | `decisionMakerId, cadence` | `SetCadenceHandler` | dm 不存在 |

★ **白名单**（`commands[].type` 的合法集）由**代码**维护，注册期收全量 `commandTypes`（`Shell.java` 已有 `commandTypes` 集合，`:209-213`）；★ **`sd` 自己的命令也不允许出现在 `DirectiveCommand` 里递归生成指令**（防无限自指）——v1 明确禁。

★ **每条命令都遵守**：`Command → ChangeSet → Revision`（铁律 2）；`SdChangeSet` 从完整 `SdState` 派生 + 往返不变式（铁律 5）。

---

## §五 变更集与时间推进

### 五.1 `SdChangeSet`

```
record SdChangeSet(FieldDelta<Nation> nations, FieldDelta<Army> armies,
                   FieldDelta<Combat> combats, FieldDelta<CombatState> combatStates,
                   FieldDelta<DecisionMaker> decisionMakers,
                   FieldDelta<Directive> directives, FieldDelta<Effect> effects,
                   FieldDelta<Verdict> verdicts, FieldDelta<LossRecord> lossRecords,
                   FieldDelta<SdInfoEntry> info)      // R14
    implements ChangeSet
```

- 复用 `FieldDelta`（`FieldDelta.java:66`；map/social/unit 共用同一份差异 + 重建语义，`UnitChangeSet.java:22` 为模板）；
- `between` / `apply` / `isEmpty` 三件套照 `UnitChangeSet.java:25/:32/:39`；
- **main `ChangeSet` 实现者从 4 变 5**（§一.3 第 4 点）；
- 往返守卫用 `RoundTripAssertions.assertRoundTrip`（`RoundTripAssertions.java:21`，**main 源码**）。

### 五.2 恰一个 `TimeParticipant`（R6 / N6 / N1）

`SdTimeParticipant implements TimeParticipant`，`namespace()="sd"`（`TimeParticipant.java:13`）。

- ★ **每个 namespace 只能有一个 participant**（`putIfAbsent` 重复即抛，`TimeAdvance.java:124-127`）⇒ 延期效果、阶段推进、配额时间流逝**全部归这一个** participant；
- `simulate(state, range)` **纯函数**，拿**同一份 base**，参与者按 namespace **字典序**定序（research §A.2；C25）；
- **能力边界**：能读 `state.module(任意 ns)`（如 `UnitTimeParticipant.java:146-154` 读 map）、用 `range.to` 评估未来、产出**本模块** `ChangeSet`；**不能**写状态、不能依赖调用顺序（research §A.2）；
- **R6 的落点**：延期效果 = 在 `simulate` 里用 `range.to` 求值 `Effect.trigger`，达标才把 `EffectStatus` 推到 `FIRED` 并产出 `sd` 的 `ChangeSet`；★ **绝不**放 ③Resolve（`TimeProposalResolver.resolve:61` 只做地址集合相交）；
- **N1 的落点**：阶段 exit 条件满足 ⇒ 计算下一阶段 entry（链式）⇒ 产出 `CombatState.currentStage` 的变更；
- **`reads/writes` 必须显式声明**（`TimeProposal.java:22`；canonical 地址字符串，v1 只做精确集合匹配）：`reads` = 条件涉及的 map/unit/sd 地址；`writes` = 本 tick 会改的 `sd:*` 地址。★ 声明直接影响与 map/unit participant 的冲突面（§五.3）。

### 五.3 ★ 跨模块效果：结构性回答

**问题**：`sd` participant **只能写 sd 数据**（`TimeProposal` 单一 namespace + 本模块 `ChangeSet`），但 R6 的"效果"可能要求改 unit（战损/编制）、map（阶段移动）。simos 六步里**没有**"跨模块写"的通道（research §C③ / D-R9）。

**设计答案（★ 已裁定，§〇.3）**：**pending-command 队列 + app 层 drain**。

1. `sd` participant **只写 sd**：把跨模块效果落成 sd 自己的 `EffectAction.EnqueueUnitCommand(type,payloadJson)`，状态 `FIRED` 但**命令尚未提交**；
2. **app 层新增 `SdCommandDrain`**：在一次 `AdvanceTime` **提交成功之后**，读取新 head 的 `sd` 切片里 `FIRED 且未 drain` 的指令，按顺序经 `CommandBus.submit(new CommandEnvelope(...))` 提交；
3. `Envelope` 的 `expectedRevision` 用 drain 前读到的 head；并发撞车由既有乐观并发 + 主键折 `Conflict` 处理（Task 12 已有机制）。

**为什么不是"依赖对应模块的 participant"**：① map/unit 的 participant 各只能有一个，语义上属于它们自己的 tick 行为，塞 sd 的效果会越界；② 条件必须在 **sd 侧**求值（只有 sd 知道 Nation/Combat 语义）；③ 铁律 3：sd 不得直接改他人数据，**必须经命令**。

★ **代价（必须记）**：drain 是"提交后再提交"，跨多个 revision；若中途失败，会出现"sd 已记 effect、unit 未改"的中间态——v1 以**幂等 + 可重放**补偿（drain 结果本身也读 sd 状态），并把"跨 revision 的原子性"列为挂起项（§十二）。★ 另有更简单的备选：**把跨模块效果也留成 Directive 的后续命令**、由玩家/Agent 下一 tick 再发——但这牺牲"自动发生"，与 R6 初衷冲突。

---

## §六 INFO 写路径（R14，全新）

**现状（research §C①，逐条）**：`InfoSystem` 只有 `get`/`put`、**无 `remove`**（`InfoSystem.java:12-18`）；`put` **零 main 调用者**；**无 INFO 命令**；Info **不进 `WorldChangeSet`**；`Replay.applyWorld` **原样保留** `base.info()`（`Replay.java:221`）；只随 checkpoint 信封往返（`CheckpointEncoder.java:68` / `Replay.readInfo:245`）；**无 `InfoKey`**（key 是普通 String）、`value` 是**裸 `Object`**（`InfoEntry.java:16`）。

**R14 的答案**：把 INFO **纳入 `SdState` / `SdChangeSet`**（选项 a）——sd 自己拥有一个 **`Map<Address, List<SdInfoEntry>>` 覆盖层**，写路径 = `sd.PutInfo` 命令 → `SdChangeSet.info` → 进 revision（可重放、受铁律 5 往返守卫）。

- **不复用全局 `InfoSystem`**：它与 sd 是两套（research D-R14）；v1 明确 sd 只服务自己的三类 INFO——**国家自然语言总介绍**、**交战详情/判决记录**、**决策执行原文**（brainstorm §1/§3.3/§4）；
- `SdInfoEntry` **自造**（★ 已裁定，§〇.3；**不复用 `InfoEntry`**）：`record SdInfoEntry(String key, Object value, Optional<String> note, RevisionId at, Optional<DirectiveId> sourceDirective)`——**不拖** `TimeRange`/`SubjectId` 的时态语义；
- ★ **ground truth vs perception**（N6 / research §B.6）：`Address` 是**任意地址**（判决可"给其他地址改 INFO"，brainstorm §4）⇒ 这个覆盖层是**感知/叙事侧**；`redaction` 层负责**按角色裁剪**，ground truth 仍由 map/unit 等领域模块持有。★ **写的是真值还是感知**需在实现期明确（本 spec 倾向：`sd.PutInfo` 默认写**感知层**，供 UI/AAR 展示；真值保留在领域模块）。

★ 兑现 `InfoEntry.java:11-12` 的告诫：**影响领域计算的字段不许走 Info**。

---

## §七 可见性与 GM 配权（R10 + N6 + N9 + N11 + N12）

### 七.1 两层机制（工具白名单 ≠ 数据脱敏）

- **工具白名单**（已有）：`AgentPermissionSet.allowedTools/deniedTools/readOnly` 被 `PermissionChecker` 消费（research §A.3）；但 MCP 整 server **只有一个 `ToolContext`**（`Shell.mcpCaller()`，`Shell.java:353-358`），**无 per-session 身份**（`:337` class doc）。
- **★ 数据 redaction 层（新建）**：实测 `QueryService` 四个读方法 `stateAt/resolve/facets/facetNames` **都没有 caller/agent/scope 参数**（`QueryService.java:52`）⇒ **插桩点必在 app 层**（research §A.3）；读工具 `execute` **只读 `context.arguments()`、从不读身份**（同上）。

### 七.2 `ViewScope`（配权数据）

```
record ViewScope(Set<RegionId> visibleRegions, Set<HexCoord> visibleHexes,
                 Set<UnitId> visibleUnits, boolean seeOwnUnits,
                 DisclosurePolicy adjudicationDisclosure,
                 Set<String> redactedFields)
```

- **GM Agent** 用 `sd.SetViewScope` 为**每个决策 Agent 灵活定制**可视范围（R10）；
- ★ **配权本身是数据**：`ViewScope` 存在 `DecisionMaker` 里、进 `SdState`/revision ⇒ **可回放、可回退分岔**；
- ★ **配权动作过审批留痕**：复用 `ApprovalCoordinator`（`Shell.java:229-244`），`sd.SetViewScope` 标 `sensitive=true` ⇒ 走 `ToolGate.Ask`。

### 七.3 redaction 插桩点（app 层）

- 读端点（`GuiServer.java:104-112`）与读工具**统一经一层 `RedactingQueryService`**：先按调用者身份取 `ViewScope`，再裁剪 `QueryService` 结果；
- ★ **两处调用者身份不同**：GUI 是玩家、MCP 是 `DEFAULT + unrestricted + external()`（`Shell.java:353-358`）⇒ v1 先支持"**GUI 用 GM/玩家 scope；MCP 决策 Agent 用其绑定 scope**"，per-session 身份列为挂起项；
- **否定式报告 / prompt injection** 的告警（research §B.6/§B.8）：redaction **只报可观察项**，不报"未探测到 X"；任何 NL 通道假定可被注入。

### 七.4 配额（N12）

- **服务端强制**：`sd.IssueDirective` 的 R4 唯一性 + 命令白名单 + 状态不变量；
- **与发 revision 同一事务**：命令的 `ChangeSet` 应用与 `Timeline.appendRevision` 同在 `CommandBus` 提交的一段（`CommandBus.java:64/:117`；Task 10 的锁只罩 ③④，Task 11 的事件链归 `commit`）⇒ "检查 + 写"在**同一 revision 事务**里，不会出现"检查通过但没写"或反之。★ 并发两条同 tick 由乐观并发 + 主键挡（Task 12 机制）。

### 七.5 决策提交渠道与身份（N16 / N17 / N18）

- 用户的输入**不一定全是同一个角色** ⇒ **允许通过多种渠道接入不同人的决策**；渠道契约定型见 **§十三**；
- ★ **视图必须按 actor 的 `viewScope` 取（N17）**：渠道只**交出 actor 身份 + 决策请求**，**拿不到全量**——脱敏视图在 **sd 侧**构造，否则本节的 redaction 当场失效；
- ★ **身份由渠道声明 + 模块校验（N16）**：渠道只可声明 `representableActors()`，**actor 是否属于该集合由 sd 侧判**；自定义渠道 = **新攻击面**，鉴权复用 `AccessToken` / `AgentPermissionSet`（research §A.3）；
- ★ **留痕（N18）**：**谁经哪条渠道提交**进 `verdictMeta` / 事件（渠道 id + actor），见 §三.5 的 `VerdictMeta` 与 §十三；
- ★ 与 R9 一致：**渠道只是"落点适配器"，不是新语义**——它最终仍写同一落点（`sd.IssueDirective` / `sd.SubmitVerdict`）。

---

## §八 判决流程 + AI 断点表 + 专用工具

### 八.1 判决流程（R9 / N7）

```
tick 开始
  → 各决策人产 Directive（人经 GUI / Agent 经窄工具）        [sd.IssueDirective]
  → SdTimeParticipant.simulate(range.to)
        ├ 评估 Effect.trigger（延期效果，R6）
        ├ 评估 CombatStage.entry/exit（N1）
        └ 产出 SdChangeSet（含 FIRED effects、阶段推进、配权/信息变更）
  → CommandBus ③④⑤⑥（锁内复查 + 单事务落 revision + checkpoint）
  → SdCommandDrain（app，§五.3）：把跨模块效果经 CommandBus 提交
```

★ **判决是数据**（N7）：裁决结果写 `Verdict`，进 revision；**回放/分岔不重跑 LLM**。

### 八.2 ★ AI 决策断点表（D1~D8）

> 每个断点 = `DecisionAdjudicator` 的一次调用；**D1 + D3 合并为同一次调用**。输入是**模块已脱敏的视图**（§七），输出 schema **先过模块内的 schema + 约束校验**（N10）。

| # | 断点 | 谁（角色） | 触发 | 输入（脱敏后） | 输出 schema（要点） | 专用工具 | cadence |
|---|---|---|---|---|---|---|---|
| **D1** | **战斗阶段裁决** | 裁决 Agent（White Cell 工具） | 当前阶段 `exit` 条件满足 | 双方**感知视图**（己方真值 + 己方探测到的对方，各自 redact）+ 阶段 `OutcomeTable` + 地形 | `StageVerdict{stageId, selectedOutcomeId, casualtyDeltas[], rationaleText}` | `sd.SubmitVerdict` | 每个交战每 tick（阶段活跃时） |
| **D3** | **情报披露裁量**（★ 与 D1 合并） | 同 D1 | 同 D1 | 同上 + 披露策略 | 并入 `StageVerdict.disclosure[]` | `sd.SubmitVerdict` | 同 D1 |
| **D2** | **决策人出令** | 决策 Agent | 该 dm 的决策周期到达（`decisionCadenceTicks` + 行政余额） | 该 dm `ViewScope` 内的感知视图 + 己方 INFO + **命令白名单** | `DirectiveDraft{directiveId, intentText, commands[](type+payload), effects[]}` | `sd.IssueDirective` | **每 tick ≤1**（R4） |
| **D4** | **接战·脱离·撤退** | 战术裁决 / 决策 Agent | 敌方进入感知范围、或接战判据成立 | 局部感知视图 | `EngagementDecision{action: ENGAGE\|DISENGAGE\|WITHDRAW, unitIds[], targetCombat?}` | `sd.IssueDirective`（task 子类） | 事件驱动（每次接触） |
| **D5** | **编制拆合意图** | 决策 Agent | 编制变更条件出现（**同位置**、状态=移动） | 己方编制树 + 单位状态 | `FormationIntent{op: SPLIT\|MERGE, rootUnitId, subUnitIds[]}` | `sd.IssueDirective` | 事件驱动 |
| **D6** | **战后处置** | 裁决 / 决策 Agent | 一个 `Combat` 全部阶段结束 | 战斗结果汇总（ground truth 或披露后）+ 损失记录 | `PostCombatDecision{disposition, garrison?, pursuit?}` | `sd.SubmitVerdict` / `sd.IssueDirective` | 每场战斗收尾 |
| **D7** | **外交** | 决策 Agent（国家） | 行政周期 / 对方动作 | 通用外交信息（双方公开态） | `DiplomaticAction{...}` | `sd.IssueDirective` | 行政 cadence |
| **D8** | **内政·行政** | 决策 Agent（国家） | 行政余额可用 | 己国区域/人口/资源视图 | `DomesticAction{...}` | `sd.IssueDirective` | 行政 cadence |

★ **cadence 与 order-latency 分开**（N5）：上表 cadence = **决策周期**（"多久能下一次决心"）；**命令延迟**（"决定→执行开始"）是**独立通道**，作用于**预先计划好的命令也一样**（research §B.8）。v1 只落"每命令一个延迟值"（§〇.3）。

### 八.3 专用窄工具（N9）

- `sd.SubmitVerdict`、`sd.IssueDirective`、`sd.SetViewScope`；
- ★ **不给决策 Agent 通用 `simos.command.submit`**：现状 M5 的 12 工具里**有**它（`CommandSubmitTool.java:35`，`SimosToolSource.java:84`，spec `level(DEFAULT,true,false)` `:72`）⇒ **这是要改的点**——`simos-app` 的工具面按 Agent 角色分载：**GM 桶**（配权 + 窄工具，**无通用写**）、**决策 Agent 桶**（仅窄工具）、**外部 MCP 桶**（保留现状或另行收敛，列为挂起）。
- ★ **GM 绝不握通用写**（N11）；窄工具的实现仍是 `CommandHandler`（`sd.*`），**最终仍走 `Command → ChangeSet → Revision`**（铁律 2）。

### 八.4 ★ 绝不交给 AI 的东西（代码判定）

1. **客观约束**：谁在哪、打不打得到、是否同格；
2. **损失上界**（N3 的 \|Δ\| ≤ 当前值）；
3. **效果引用合法性**（地址 + 命令白名单）；
4. **阶段进入/退出条件**（N1 的 entry/exit 判定）。

⇒ AI 只做"**在合法选项里选一个 + 写理由**"；以上四类**在 handler / participant / guard 里用代码判**。这也是 N14 的判据基础。

### 八.5 `DecisionAdjudicator` SPI（N10）

```
interface DecisionAdjudicator {                 // 定义在 simos-sd（N10）
  String name();
  Judgement adjudicate(AdjudicationRequest request);
}
sealed interface Judgement { Accepted(String payloadJson) | Abstained(String reason) | Failed(String reason) }
record AdjudicationRequest(String breakpoint, String redactedBriefJson,
                           String outputSchemaJson, String commandWhitelistJson)
interface LlmClient { String complete(LlmRequest request); }   // app 实现；测试注 FakeLlmClient
```

- **边界内聚（模块内）**：契约、schema/约束校验器、**脱敏视图构造**、工具名单、配额强制、**判决冻结**；
- **执行上浮（模块外）**：**key / 配额 / 重试 / 超时**留在 app 的 `LlmClient` / `AdjudicatorRunner`；★ `LlmClient` 是接口 ⇒ 测试注入 `FakeLlmClient`（N10），**判据可完全离线**；
- ★ 若 AgentLib 已有合适的 LLM 客户端，app 侧可适配；**本 spec 未核实其 API**（§十四）。

### 八.6 N13 降级

超时 / 输出非法 ⇒ `Judgement.Failed`/`Abstained` ⇒ **明确行为**：该断点本 tick **无判决**（`Directive`/`CombatState` 保持前态）+ 写一条事件/INFO 留痕；**绝不卡死整个 tick**，下一 tick 继续。★ 与 LLM 兵棋已知失败模式（重复、幻觉出新计划、opacity）对应（research §B.8）。

### 八.7 提交渠道（交叉引用 §十三）

判决/出令的**输入来源不唯一**（人 / Agent / 外部系统）⇒ 由 **`DecisionChannel`（§十三）** 接入；渠道**只做落点适配**，`sd.SubmitVerdict` / `sd.IssueDirective` 仍是**同一落点**（R9）。★ 渠道的身份与视图约束见 **N16 / N17 / N18**（§七.5）：**actor 合法性由模块校验、视图按该 actor 的 `viewScope` 取、渠道 id + actor 进留痕**。渠道**不参与**判决语义，也**不产生**新的裁决步骤——它只是把不同人的输入送进 §八.1 的同一条流程。

---

## §九 跨模块守卫（"带 Nation 标签的区域不可删"）

**实测（research §C②）**：全仓 main 源码 **无** `Validator`/`interceptor`/`canDelete`/`DeleteGuard`；唯一写时协调是 `TimeProposal.reads/writes` + `TimeProposalResolver.resolve`（地址级、非语义级、**只在 AdvanceTime 支**）；`RegionOperations.deleteRegion` 只校验"目标存在"（`RegionOperations.java:109/:112`）；最接近的形态是 **`AgentAttachPolicy`**（`util.spi:17`）——**只读、只回答可不可以**、**不在写命令路径上**（research §C②.4）。⇒ **必须新造机制**。

**设计（照 `AgentAttachPolicy` 形制）**：

1. 在 `io.mosire.simos.util.spi` **新增**一个写前策略契约（命名建议 `MutationGuard`，**只读**）：
   ```
   interface MutationGuard { String name();
     Optional<String> rejection(SimulationState state, String commandType, String payloadJson); }
   ```
2. **app 装配**（`Shell`）：注册 `RegionDeleteGuard`（实现放 `simos-sd`，因为只有它能同时读 map 的 `Region.meta.tag` 与 sd 的 `Nation.homeRegion`）；
3. **Core 的调用点**：在 `CommandBus` 的**信封支**（`dispatch`）里、`handler.handle` **之前**依次调用已注册 guard；任一拒绝 ⇒ 照 `HandlerOutcome.Rejected` 落 `received+rejected`（不留 revision）。
4. ★ **Core 仍看不见领域类型**（ADR-1）：guard 是 `util.spi` 里的**不透明策略**，Core 只按 `commandType`/`payloadJson` 转发——它不知道"Region"或"Nation"是什么，⇒ **铁律 4 不破**。

**判据（§十一.13）**：带 tag ⇒ 拒；去掉 tag ⇒ 放行（证明**不是恒拒**）。★ **必须自证**：删掉 guard 装配 ⇒ 该用例应红。

★ **范围**：v1 只覆盖"**带国家 tag 的区域不可删**"。其余跨模块写前校验（如"有驻军的区域""交战中的 hex"）逐条按需新增 guard，**不在本 spec 预设**。

---

## §十 与 Unit 扩容的边界

**SDSimos 只管国家·交战·决策**（§一.1）。Unit 的进一步开发**归 `unit.*`**，本 spec 只划边界、不实现，**将另写 `2026-09-20-unit-extension-design.md`**。

brainstorm §7 的**五个结构性缺口**与归属：

| # | Unit 缺口（实测） | 归属 | sd 侧只做什么 |
|---|---|---|---|
| ① 无"集合单位" | 严格单父树（`UnitState.java:74-97`），无子列表/多父/`Army` 容器 | **N4 = 两层**（`command_chain` 可多属 + `Formation` 严格树 + attached/detached + 相对偏移，§〇.3）；**编制结构在 unit** | sd 存 `Army` 的**归属关系**（`NationId` ↔ 单位根），引用不变式 §三.1.2 |
| ② 无作战状态 | `Unit` 无 status/mode（`Unit.java:20-29`）；`MovementStatus` 只是路线进度、现算不落库（`MovementStatus.java:4-10`、`UnitMoves.java:31-74`） | **`unit.*`** 新增三态（移动/休整/交战）与速度映射 | sd 的接战/脱离决策**引用**单位状态（reads），不拥有它 |
| ③ 无战损语义 | 只有整份替换 `unit.SetStrength`（`UnitOperations.java:82-97`） | **`unit.*`** 新增**增量**命令（暂名 `unit.ApplyCasualties`，**以 unit-extension spec 为准**） | sd 产 `CasualtyDelta`（N3）+ 代码侧上界校验；跨模块落经 §五.3 drain |
| ④ `PlanRoute` 不容稀疏路点 | handler 写死 `new Route(waypoints, waypoints)`（`PlanRouteHandler.java:44`）；A\* 已存在（`PathFinder.java:59-107`，生产调用者 `GuiServer.java:421`） | **`unit.*`** 新增稀疏路点载荷通道 | sd 的"回归路径"若要 A\* 补中间，**用 unit 的新通道** |
| ⑤ `ReparentUnit` 只改单点 | 一次一个父（`UnitOperations.java:47-64`）；`DisbandUnit` 要求先改编（`:162-176`） | **`unit.*`** 新增整棵子树迁移 / 拆合操作 | sd 的编制拆合**意图**是数据（D5），执行走 unit |

★ **unit 与 social 编译期互不可见**（`simos-unit/pom.xml:47-49`、`simos-social/pom.xml:47-49`）⇒ **国家（map 的 Region tag）与军队（unit）的关联只能在 SDSimos / app 层做**（brainstorm 末行）。
★ **三态与拆合的规则在 unit**；sd 的 D5 只表达"想拆/想合"的**意图**，合法性（同位置、状态=移动）由 **unit 的命令**判（§八.4：约束归代码）。

---

## §十一 判据（可实测；每条配变异体思路）

> 纪律：**判据不断言 LLM 的具体选择**（N14），只断言结构 + 约束 + 冻结 + 可回放；**每条护栏都要有故意违规用例自证**。

| # | 判据（实测项） | 变异体思路（去掉被保护的那行 ⇒ 用例应红） |
|---|---|---|
| 1 | **模块边界自证**：`simos-sd` 可依赖 agentlib + 四模块；**core main 不得依赖 sd**（enforcer） | 把 `simos-sd` 加进 core main 依赖 ⇒ enforcer 应拒 |
| 2 | **R4 单指令**：同一 dm 同 tick 发第二条 `sd.IssueDirective` ⇒ 拒绝、`revisions` 行数不变 | 删唯一性校验 ⇒ 应允许第二条 |
| 3 | **N7 判决冻结**：含判决的推进后 `Replay` ⇒ `SdState` 逐字段相同、**`FakeLlmClient` 调用计数 = 0** | 在 replay 路径调 adjudicator ⇒ 计数 > 0 |
| 4 | **N8 verdictMeta**：判决行 `model/promptVersion/inputBriefDigest` 非空 | 删任一字段 ⇒ 拒绝 |
| 5 | **N1 条件驱动**：`AtOrAfterTick` 与 `ThresholdKills` 两类条件各实测推进；**上一 exit == 下一 entry** 不满足时命令拒绝 | 用时间硬编码替代条件 ⇒ 阶段推进点不符 |
| 6 | **N2 categorical**：outcomeTable 权重归一、**恰选一个**；同一 revision 两次读出**逐字节相同** | 允许 0 或 2 个 selected ⇒ 用例红 |
| 7 | **N3 双轨 + 上界**：Δpersonnel/Δequipment 逐项为负且 \|Δ\| ≤ 当前；越界拒绝；`LossRecord` 可回放 | 删上界校验 ⇒ 越界通过 |
| 8 | **R6 延期效果**：条件未达成前 tick 推进**不**产生效果；达成后**只产生一次** | 把条件评估挪进 ③Resolve / 未达成即产生 ⇒ 用例红 |
| 9 | **变更集往返**：`SdChangeSet` 全组件（含 `info`）`assertRoundTrip` 绿；`ArchitectureGuardsTest` 计数 = 5 | 漂移一个字段 / 让计数保持 4 ⇒ 红 |
| 10 | **R10/N6 可见性**：两个不同 `ViewScope` 的决策人调同一读端点 ⇒ 返回**不同**数据（对手/未探测项被 redact）；`sd.SetViewScope` 写入 revision | 去掉 redaction 插桩 ⇒ 两响应相同 |
| 11 | **N9 窄工具**：GM 与决策 Agent 的工具有效集里**都没有** `simos.command.submit`；有 `sd.*` 窄工具 | 把 `command.submit` 加回 ⇒ 断言红 |
| 12 | **N12 配额同事务**：并发两条同 tick 指令 ⇒ 恰一条成功、另一条拒绝/冲突，**无半写 revision** | 把配额检查挪到事务外 ⇒ 出现两条或半写 |
| 13 | **§九 守卫**：带 Nation tag 的 Region `map.DeleteRegion` ⇒ 拒；**去掉 tag ⇒ 放行** | 删 guard 装配 ⇒ 应删除成功 |
| 14 | **N13 降级**：`FakeLlmClient` 抛超时 / 返回非法 JSON ⇒ 本 tick 明确降级、**不卡死**、后续 tick 继续 | 让异常逃逸 ⇒ tick 中断 |
| 15 | **§五.3 drain**：跨模块效果经 `SdCommandDrain` 落成真 revision；drain 幂等（重放不重复提交） | 去掉幂等键 ⇒ 重复提交 |

★ **门禁**：`./mvnw clean verify` rc=0；`./mvnw -pl simos-sd -am verify` 等；变异轮**全杀**（按 CLAUDE.md 的"护栏必须自证"五条形态，尤其"跑之前先让变异体自证 md5"与"读 surefire 必须先跑干净轮")。

---

## §十二 挂起 / 不实现（带裁定）

| 项 | 处置 | 依据 |
|---|---|---|
| **"hex 的对应状态可变"** | ★ **只列计划、不实现**（用户原话） | brainstorm §3.3 |
| **撤销 / 重做** | **不做**；要撤销就**回退到前一节点（分叉）** | 同 M8 Q1 |
| **预制 command** | **不做**（未想好；等开模拟几轮再定） | brainstorm §4 |
| **"决策立刻生效"的 Command** | **不做**（用户倾向"没必要也不能"） | brainstorm §4 |
| **跨 revision 的 drain 原子性** | 挂起；v1 以幂等 + 可重放补偿 | §五.3 |
| **`RECOVERABLE` 回池速率** | 挂起（v1 只记类别） | §〇.3 / N3 |
| **HQ 积压/饱和** | 挂起（v1 order-latency 每命令一个值） | §〇.3 / N5 |
| **per-session MCP 身份** | 挂起（现状无 per-session，`Shell.java:337`） | research §A.3 |
| **`LlmClient` 的真实后端/key/重试/超时** | 挂起（归 app；本 spec 不实现） | N10 |
| **CRT / Lanchester 的具体数值真值** | **不引入**（只借机制形状） | research §B.3 / §B.9 |
| **EBO / EBAO 框架** | **不认**（只借词汇 effect/trigger） | research §B.8 |
| **全局 `InfoSystem` 写路径（选项 b）** | **不做**（R14 选 sd 自有 ChangeSet，§六） | research D-R14 |
| **`agent:` 命名空间的 resolver** | **不新建**（决策人走 `sd:`，N15） | research §A.2 |

---

## §十三 决策提交渠道（`DecisionChannel`）

> 用户追加需求（2026-09-20 原话）：「用户的输入不一定全是同一个角色——**允许通过多种渠道接入不同人的决策**，因此**应该留有自定义决策提交渠道**」。

### 十三.1 契约（★ 照 AgentLib `ApprovalChannel` 形制）

**先例（实测，AgentLib 源码）**：`ApprovalChannel`（`~/ProjectMosire/AgentLibMosire/src/main/java/io/mosire/agentlib/approval/ApprovalChannel.java:18-52`）的形制是 `name()` + `available()` + `publish(req)` + `await(id, wait)`；实现 `HttpApprovalChannel`（`HttpApprovalChannel.java:22`）**不持有 HTTP server**、可用性由装配层 `markUp()` 打开（`:72-75`），且 **fail-closed**（`await` 返回空 ⇒ 拒绝，`:62-69`）。SDSimos 的决策提交渠道**照这个形制**，方法按决策语义签：

```
interface DecisionChannel {
  String channelId();
  Set<ActorId> representableActors();
  void submit(ActorId actor, DecisionRequest req);
}
```

- `channelId()` —— 对应 `ApprovalChannel.name()`：**留痕用**（N18，进 `verdictMeta` / 事件）；
- `representableActors()` —— ★ 渠道**声明**它能代表哪些 actor（N16；**声明 ≠ 授权**，见十三.2）；
- `submit(...)` —— 渠道把输入交进 **sd 的落点**（不直接改状态）；
- ★ **可选** `boolean available()` —— 照 ApprovalChannel 的"**可用性是第一公民**"，装配层在端口真的监听之后再 `markUp()`；★ 自定义渠道的可用性语义由实现自定（本 spec **不强制**，列为待定）。
- ★ `ActorId` / `DecisionRequest` 是**新类型**（住 `simos-sd`）；`DecisionRequest` 只承载"actor 想做什么"（`IssueDirective` 草稿 / `SubmitVerdict` 草稿 / 自由文本），**不含视图数据**。

### 十三.2 模块侧强制（渠道**不得**自己实现）

★ 以下四条**必须由 `simos-sd` 做**，渠道实现**不得**代劳：

1. **`actor ∈ representableActors()` 校验**——渠道**不得冒称**任意 actor（N16）；
2. ★ **视图必须按该 actor 的 `viewScope` 取**——渠道**拿不到全量**；脱敏视图由 sd 侧构造（§七.3 / N17）；
3. **1 令/tick 与发 revision 同事务**——`sd.IssueDirective` 的 R4 + 事务边界（§七.4 / N12）；
4. **留痕**——渠道 id + actor 进 `verdictMeta` / 事件（N18）。

### 十三.3 app 侧适配（★ 新增渠道不改领域代码）

- **GUI / MCP / CLI / 外部 HTTP 各实现一个** `DecisionChannel`；
- ★ 添加一条新渠道 = **app 层加一个实现 + 装配一行**（§一.4 第 9 条），**`simos-sd` 一个字不改**；
- 外部 HTTP 通道照 `HttpApprovalChannel` 的先例：**不持有 server**，可用性由装配层打开。

### 十三.4 与 R9 的关系 + 攻击面

- ★ **渠道只是"落点适配器"，不是新语义**：无论从哪条渠道进来，最终都写**同一落点**（`sd.IssueDirective` / `sd.SubmitVerdict`），走**同一条 `Command → ChangeSet → Revision` 路径**（铁律 2）。渠道**不预设**"立刻生效"、**不绕过**配额、**不放大**权限；
- ★ **攻击面（N16，必须记）**：自定义渠道是**新入口**，身份声明可被伪造执行；故"**身份以模块校验为准、视图由模块构造、留痕强制**"三条是**安全边界**，不是风格问题。

---

## §十四 本文未核实项与已做的假设（诚实清单）

**未核实（research 已自陈）**：

- `§A.2` / `§A.3` 的多数 `文件:行` 来自 explore transcript（◇），**本会话未逐行复核**；行号漂移以源码为准。
- **AgentLib 的内部语义**（`PermissionChecker` 顺序、`ResourceScope` 前缀、是否有可用的 LLM 客户端）：来自 transcript，**本会话未复核**（§八.5 只把 `LlmClient` 定为**本模块自有的接口**，不假设 AgentLib 提供）。
- **"更细粒度 resource scope 是否真能表达 per-hex"**：transcript 断言"理论上可、零代码如此用"，**未验证** ⇒ §七 的 redaction 因此**不依赖** resource scope，而是新建数据层。
- **Command Ops order-delay 精确公式**：未公开（research §B.8）⇒ §〇.3 只落"每命令一个延迟值"，**不照抄数值**。
- **RAND 2025"裁判叙述泄漏"**：仅播客次源，**未核实** ⇒ 只作"否定式报告要警惕"的定性提示。
- **`P(t) model`**：无规范名（research §B.8 自陈）⇒ 本 spec **不使用**该词。
- **§B 外部研究未逐 URL 回源**：可靠性标签取自原研究自评。

**我替用户做的假设（凡此均应视为可推翻）**：

1. **`simos-sd` 的 Maven 坐标/包名/子包划分**（§一.2）——按项目命名惯例推定；
2. **`sd` 命名空间的地址形态**（§二.2）——N15 只裁"不可 `agent:`"，具体形式由本 spec 提出；
3. ~~**`SdInfoEntry` 自造**（§六）——R14 子问题，§〇.3 建议项~~ ⇒ ★ **已被用户 2026-09-20「开始吧」采纳为裁定**（§〇.3），不再是假设；
4. ~~**跨模块效果走 pending-command 队列 + app `SdCommandDrain`**（§五.3）——§〇.3 建议项~~ ⇒ ★ **已采纳为裁定**（§〇.3），不再是假设；
5. **各命令的 `type` 名与字段名、各 record 的字段名**（§三/§四）——设计形状，实现期可微调；
6. **§九 契约命名 `MutationGuard` 与 Core 调用点（信封支 `dispatch` 前）**——形制照 `AgentAttachPolicy`，具体接口形状由本 spec 提出；
7. **AI 断点的输出 schema 名与字段**（§八.2）——设计形状；
8. **判据条目与变异思路**（§十一）——由本 spec 提出，非用户逐条确认。

**编号说明**：R7 / R8 / R11 / R12 / R15 是**早前的提案编号，已被取代或合并**（去向见 §〇.1 表下注记），**不是未决项**；**N4 及 §〇.3 其余四项已由用户 2026-09-20「开始吧」采纳为裁定**——本 spec **无遗留待裁项**。
