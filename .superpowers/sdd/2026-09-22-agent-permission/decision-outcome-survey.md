# 「决策结果」现状调查（只读，2026-09-23）

> 需求来源：用户 2026-09-23。原话见台账 §6.3。本文件是**事实基础**，供裁定后做设计。
> ★ 行号是**工作树当时字节**的行号（含当时未提交的改动）。
> ★ 本文是调查 agent 报告的**精炼版**（原文更详，含每个结论的 `文件:行` 证据链）。

## 〇 最重要的一条：`Directive.commands` **从未被执行**

全仓 `git grep` 只有**两处**迭代 `DirectiveCommand`：
- `ApiViews.java:643-649` —— **渲染**（进 GUI 的 `commands` 字段）
- `IssueDirectiveHandler.java:156-166` —— **白名单校验**

⇒ **没有第三处把它 `submit` 出去**。**一条 `Directive` 落下去，它的 `commands[]` 对世界产生的实在效果 = 0**。
能改变世界的只有 `Effect.action`（`Action.EnqueueUnitCommand`）→ `SdCommandDrain` 那条路
（`commandId = "drain:" + effectId`，`SdCommandDrain.java:90-92`）。

★★ **这条直接决定需求⑤的前提**：用户要"把单个决策的对地图实在效果归到决策结果里"，
而**那些命令根本没生效** ⇒ "实在效果"目前是**空的**。

## 一 INFO 的现状

**有两套，彼此不通**：

| | 全局 `InfoSystem`（util） | **sd 侧 INFO 覆盖层** |
|---|---|---|
| 类型 | `InfoEntry`（`util/info/InfoEntry.java:16-17`） | **`SdInfoEntry`**（`sd/model/SdInfoEntry.java:17-22`） |
| 形状 | `key/value/valid/source/note` | `key, Object value, Optional<String> note, RevisionId at, Optional<DirectiveId> sourceDirective` |
| 载体 | `SimulationState.info()` | `SdState.info()`（第 10 组件，键 = **canonical 地址串**） |
| 写入口 | **无**（main 全传 `InMemoryInfoSystem.empty()`） | **5 条**（见下） |
| 进 revision / 可回放 | ❌ | ✅（`SdChangeSet.info`，受铁律 5 往返守卫） |

⇒ **用户说的 "INFO 就是决策结果" 只能落在 sd 侧那一套**；全局那套是空转遗留。

**写 sd INFO 的 5 条路径**：`sd.PutInfo`（GM 工具，任意地址）· `sd.IssueDirective`（`sd:directive.<id>` / key 固定 `"intent"`，
且填 `sourceDirective`）· `sd.StartDecision`（`sd:decision.<dmId>` / `"start"`）· `sd.RunDecision`（同址 / `"run"`）·
`SdTimeParticipant` 的 `Action.PutInfo`。
★ **`sd:decision.<dmId>` 的 `start`/`run`：写了但全仓没有任何生产读者。**

**「INFO 的唯一 id」：没有**（`SdInfoEntry` 五分量里无 id）。定位 = （地址串, key, 列表下标）；追加式、无去重。

## 二 `Directive` 与 INFO 的关联

`Directive.intentInfoKey` 是**纯 key**（固定 `"intent"`），地址由 `DirectiveId` 反推（`"sd:directive." + id`）。
**写**：只有 `IssueDirectiveHandler:111-118` 一处。**读**：只有 `SdQueryService.infoByDirective:178-190`
（它**认 `sourceDirective` 而不是拼地址串**）⇒ 产出 GUI 的 `intentInfo`/`intentInfoKey`（「决心 / 理由」）。

## 三 `Directive.effects`

`Set<EffectId>`（保序不可变）。命令期校验"引用的 Effect 必须存在"；状态期再校验一次。
`Effect = (id, kind, trigger, action, status, createdTick)`，5 态。
★ `SdCommandDrain` 消费的**不是** `Directive.effects`，而是 `SdState.effects()` 里 `status == FIRED`
且 `action instanceof Action.EnqueueUnitCommand` 的**全部**效果（不只是某条 Directive 的）。

## 四 ★★ 「对地图的实在效果」怎么追溯

**原料齐备**：`revisions` 行 = `RevisionRow(branch, revision, parent, timestamp, **commandId**,
correlationId, initiator, commandType, **changesetJson**)`（`core/timeline/RevisionRow.java:25-34`）
⇒ "某条命令改了什么"**已经落盘**。

**但没有 join 出口**：
- GUI `/api/timeline` 只发 `revision/tick/commandType/initiator/parent`，**明确丢掉 `changesetJson`**（体积），
  **`commandId` 也没发**
- core **没暴露** `Timeline.row(StateRef)` / `byCorrelation`
- 9 条读工具里**没有一条**能按命令/修订读变更集

⇒ **技术上可 join 的链**（只存在于 `SdCommandDrain` 的命名约定里，**没有任何 API/工具/GUI/用例**）：
```
Directive.effects → EffectId → Effect.action → 时间线中 commandId == "drain:"+effectId 的那一行 → 该行 changesetJson
```

**事件库**（`EventRow`）：**无唯一 id 出到 Java 侧**（`seq` 只在 DB）；**生产零读调用者**（"只写不读"）。

## 五 决策人读 INFO：**今天完全读不到**

1. 决策人的 sd 可达面 = **只有** `sd:decision-maker/<自己 id>` 一条前缀（`DecisionCallerFactory:244-246`）
2. 范围函数**不给 sd**（`NationScope`/`ArmyScope` 只表态 `map`/`unit`/`social`）
3. **9 条读工具里没有一条**碰 INFO 或 `Directive`
4. `state.resolve` 也够不着（要求 `sd:<kind>/<id>` 前缀）
5. **GUI 的 `as=` 在那些 sd 端点上一律被拒**（`rejectAs`）⇒ **决策人视角连自己的令都读不到**
6. `RedactingQueryService` **只有 `seesHex/seesRegion/seesUnit`** 三个谓词，**没有 info 方法**
7. ★ sd spec **本来写了**决策人要读 INFO（D2 断点表"输入（脱敏后）"含"**己方 INFO**"，`:466`）——**这一半未实现**

## 六 「无主」与「多标签」

| 需求 | 现状 |
|---|---|
| **不属于任何决策人** | ✅ **`SdInfoEntry` 根本没有 owner 字段** ⇒ "无主"是**默认态、天然支持**。唯一来源 `sourceDirective` 是 `Optional` |
| **多标签** | ❌ **全仓没有任何多标签实体**：`RegionMeta.tag` 单值、`Directive.decisionMakerId` 单值、`DecisionMaker.affiliation` 单值、`Verdict.subject` 非空单值。`Set<String>` 只出现在**配置/权限**语义（`allowedTools`/`redactedFields`） |

## 七 差距清单

### ✅ 已有（可直接用 / 只差薄接线）
- **A1** sd INFO 覆盖层（进 revision、可回放、受往返守卫）—— **"决策结果 = INFO" 的物理载体已存在**
- **A2** 追加式写入 + `note`（正文位）+ `at:RevisionId`（何时）
- **A3** "按 tick 排序、新的在前"已实现过一次（`SdQueryService.order():166-170`）
- **A4** 窄写工具模式（加一条写命令成本很低，`sd.PutInfo` 是样例）
- **A5** "INFO → 主体"的反查已有实现（`infoByDirective`，认引用不拼串）—— 是"结果 → 决策"反查的**模板**
- **A6** `drain:<effectId>` 命名约定 —— ⑤ 的**链路已在数据里**
- **A7** `revisions` 行的 `commandId` + `changesetJson`（`Timeline.readChangeSet` 是 public static）

### ⚠️ 要改（扩既有机制）
- **W1** `SdInfoEntry` 加 **唯一 id / tick / 标签集** ⇒ 动 `SdState` 组件 + `SdChangeSet` 第 10 组件 + 往返守卫 + **旧档兼容**
- **W2** 决策人的 sd 可达面加一条覆盖"结果"地址的前缀（★ 要挑**不会顺带放开署名权**的形态）
- **W3** 读工具面加"列出/读取决策结果"（★ 白名单与注册表桶**两处必须同源**；新命令类型还要连带
  `CatalogTool.PAYLOAD_HINTS`（构造期拒绝缺项）+ `McpCoverageTest`）
- **W4** `RedactingQueryService` 加 **info 读取 + 按标签裁剪**（★ 硬要求：可见性规则**只能有一份**，
  与 MCP / `as=` **同一份装配、同一批判定谓词**）
- **W5** `GuiServer` 的读端点/`rejectAs`（决策结果**是数据面**，与 `rejectAs` 的既有理由不冲突，但**要逐条判**）
- **W6** 若要 ⑤ 的 join 可用，至少要把 `commandId` 送到某个读口（`changesetJson` 丢得有理由，`commandId` 没有）
- **W7** 若要 ⑤"归到结果里"，需要一条 **effect/drain-revision → 决策结果 id** 的反查（现状只有单向命名约定，**无用例**）

### ❌ 要新建（完全没有）
- **N1** "决策结果"实体本身（带 id、可无主、可多标签）—— 可**借 `SdInfoEntry` 的壳**，但**多标签字段是新增**
- **N2** "按 tick 检索/分组"的读面（`SdInfoEntry` 只有 `RevisionId`、`SdState.info` 的键是地址串）
- **N3** "一个结果可被多个决策人查看"的**权限语义**（现有权限 = 资源前缀 + 单主体派生；
  **"标签 → 可见"这条映射没有任何机制承载**）
- **N4** ⑤ 的 join 出口（决策 → 实在效果的可读视图）
- **N5** 决策人视角的条目时间线 UI

## 八 ★ 两条**必须用户先拍**的（调查者明确说"我没有依据替你判"）

**(a) 先接执行链，还是认定"效果只走 `Effect`→drain"？**
`Directive.commands` 是**死字段**（从不执行）。"把决策的实在效果归到结果里"的**前提**是那些命令**真被执行**。
- 路一：把 `commands[]` 接上执行链（决策真正改地图）
- 路二：认定"决策的落地只经 `Effect`→drain"，那 `commands[]` 该考虑**废掉或改成别的语义**

**(b) 标签挂「决策人 id」还是「自由标签」？**
- `Set<DecisionMakerId>`：与既有权限模型**同构**（只差一条前缀），但"谁能看"绑在具体决策人上
- `Set<String>` 自由标签：更灵活（"无主"= 空集、"所有人可见"也是一种配法），但会开出
  **"标签语义"这第二份真相** —— 与本仓"可见性规则只准有一份"的既有纪律**相抵**

## 九 最小落地形态（调查者的建议，**待用户裁定后才定**）

1. **新建一个 sd 实体 + 一条写命令**（如 `sd.PublishDecisionOutcome`），落点**借 `SdInfoEntry` 的既有形态**
   （它已进 revision、可回放、有往返守卫、有 `note`/`at`、追加不改语义）⇒ 最小增量 = **唯一 id + tick + 标签集**
   ★ 代价要认下：改 `SdInfoEntry` = 改 `SdState` 组件 + `SdChangeSet` + 往返守卫 + **旧档兼容**（有真档 `v17levant`）
2. **一条读取面 + 一份裁剪规则**：必须走 `RedactingQueryService` 的**同一份装配**；
   并给决策人的 sd 可达面加一条覆盖"结果"地址的前缀
3. **⑤ 第一期只做"证据链接"而不是"效果内联"**：把 `EffectId` / drain revision 的 `commandId` 当**引用字段**，
   join 由读侧现算（原料齐备）★ **不要**第一期就把 `changesetJson` 解出来塞进条目——Core 对变更集**不透明**，
   解码要回到各模块 codec，是一条跨模块新链路
