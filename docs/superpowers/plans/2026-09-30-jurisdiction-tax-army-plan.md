# 辖区 · 税/地方债 · 组军（用户裁定 2026-09-30）

> 用户原话（连读）：「**基础肯定是 MapRegion 啊，允许进行 Region 数据结构的操作，但是应当在 Unit 里搞一个对应富数据结构，
> 挂载在单位下表示管辖；组军本质上就是从地方抽人力抽经济，做好来源、行动记录就行**」。
> 性质：**设计计划**（实施按阶段派单；开发期只编译，测试统一留最后，AGENTS.md §三.0）。

---

## 0. 三条裁定的落地含义

1. **辖区的空间底座 = `MapRegion`**（`simos-map`）：`Region(id, name, hexes, boundary, meta)` 是权威；
   允许对 Region 做数据结构操作（建/改/删/改 hex 集合，现有 `RegionOperations` + `map.CreateRegion/UpdateRegion/...` 工具）。
2. **"管辖"挂在 Unit 上**：`simos-unit` 的 `Unit` 增一个**富结构** `Jurisdiction`（不是把管辖权写回 Region 的 tag）。
   Region 仍是空间事实；"谁管这片"是单位侧的制度事实。
3. **组军 = 从地方抽人力 + 抽经济**：不需要复杂后勤模型；**来源可追溯**（人/粮/钱/工具从哪来）+ **行动记录**
   （谁在何时对哪些来源做了什么）是硬要求。

## 1. 代码现状接缝（实测）

| 面 | 现状 | 对本计划的意义 |
|---|---|---|
| `Region` | `id/name/hexes/boundary/meta`；`withHexes/withName`；构造期钉死 boundary=hexes 的纯函数 | 辖区引用用 `RegionId`；校验 Region 存在可走 `GameMap.regions()` |
| `RegionMeta` | `color/tag/description/annexedBy`（都可 null） | **不改它来存管辖**（用户已裁：管辖在 Unit 上） |
| `Unit` | 14 组件 record；`parent/position/attached/offset` 走 `SegmentedSeries`，其余普通字段；有 9 参兼容构造器；文档钉死"生产拷贝点一律走 canonical，漏传=静默丢字段" | 新增第 15 组件 `jurisdiction`；所有 canonical 构造点 + codec + 兼容构造器必须同步；历史由 revision 承载，不需要 SegmentedSeries |
| `UnitChangeSet` | 组件 = `UnitState` 的 2 张表（`units`/`commandChains`），不枚举 `Unit` 字段 | 加 `Unit` 字段**不动** `UnitChangeSet` 形状；但 `UnitRoundTrip`/codec 要覆盖 |
| `ActorKind` | 含 `UNIT` | 单位国库 = `ActorRef(UNIT, unitId)` 的 `GoodsAccount`（actor 切片），税/抽成资金落点 |
| 依赖 | `simos-unit` 已依赖 `simos-map` | `Jurisdiction` 可直接用 `RegionId`；handler 可校验 Region 存在 |

## 2. 阶段 5：`Jurisdiction` 富结构 + Unit 挂载 + 管辖/税率命令

### 2.1 结构（unit 模块）

```java
/** 单位侧的管辖：管辖哪些 Region、每区域长期税率、周期性一次性抽取上限、行政能力。 */
public record Jurisdiction(
    Map<RegionId, Long> taxRatePerMilleByRegion, // key 集 = 管辖区域；value = 每周期税率(‰)；空 map = 无管辖
    long levyGrainCapPerCommand,                   // 一次性抽取：粮/次
    long levyMoneyCapPerCommand,                   // 一次性抽取：钱/次
    long levyManpowerCapPerCommand,                // 一次性抽取：人/次（组军用）
    long administrationPerMille) {               // 行政能力：0=无班子（军队只能一次性抽），>0=可长期税
  ...
}
```

- `Unit` 新增第 15 组件 `Optional<Jurisdiction> jurisdiction`（缺省 `Optional.empty()` ⇒ 旧档行为逐字不变）；
- 保序不可变（`LinkedHashMap` + 冻在赋值处；不用 `Map.copyOf`）；负值/税率 >1000 在构造期拒；
- 兼容构造器链：旧 9 参 / 现 14 参两个兼容构造器都把新组件取 `Optional.empty()`，**生产拷贝点一律改 canonical 15 参**。

### 2.2 命令（unit 域窄写，GM 桶）

| 命令 | 载荷 | 语义 |
|---|---|---|
| `unit.SetJurisdiction` | `{unitId, regions:[regionId...], levyGrainCapPerCommand?, levyMoneyCapPerCommand?, levyManpowerCapPerCommand?, administrationPerMille?}` | 整体替换管辖区域 + 缺省保持其余字段；`regions` 里每个 id 必须存在于当前 `GameMap.regions()`（不存在 ⇒ 具名拒，不静默丢）；空数组 = 撤销全部管辖 |
| `unit.SetTaxRate` | `{unitId, regionId, ratePerMille}` | upsert 某管辖区域的长期税率；`regionId` 不在该 unit 的管辖里 ⇒ 具名拒（先 `SetJurisdiction`） |

- 两条都走 `UnitChangeSet`/revision；配套 GM 窄工具 + `CatalogTool.PAYLOAD_HINTS` + `McpCoverageTest` 载荷断言（按既有工具面模板）。

### 2.3 验收（开发期）

spotless + `compile -pl simos-unit -am` + `compile -pl simos-app -am`；
测试代理输入：`Unit` 15 组件往返、旧档缺键 ⇒ 空管辖、Region 不存在拒绝、空管辖下 `SetTaxRate` 拒绝。

## 3. 阶段 6：区域税 / 一次性抽取（app 组合根，跨切片）

> 辖区在 unit、钱粮在 actor/classfirst、人口在 social ⇒ 只能由 **app 组合根**做原子命令。
> 控制方口径补裁（2026-09-30，落地前记录；与用户裁定"来源、行动记录优先"一致）：

### 6.0 落地口径（先记后做）

1. **上限语义 = 单条抽取命令**：`levy*CapPerCommand` 是**一条** `simos.unit.levyRegion` 调用的上限（0 = 该类无额度、拒）。
   "逐周期（tick）累计额度账本"**本批不建**（要动 `Jurisdiction` 增状态 + 周期边界重置，收益小）；
   字段名从 `CapPerCycle` 改成 `CapPerCommand`，不让名字撒谎。周期累计留待测试阶段后按需再裁。
2. **周期定义（只对长期税）**：一个周期 = 1 tick = 世界日；长期税每日结算一次。
3. **账本边界（本次具名，不静默）**：阶段 6 的粮/钱抽取**只动 actor 家户账 + 单位国库 actor 账**
   （`ActorKind.HOUSEHOLD` / `ActorKind.UNIT`）；`classfirst` 阶层池库存**本批不动** —— 池是"阶层级生产库存"的权威、
   家户 actor 账是"家户持有"的权威，日结算只把**池级增量**折进家户账（`ClassFirstActorWriteback` 的既定近似），
   两者绝对量本就不是硬镜像。**"全国粮总量"因此必须现算两本账之和**，不许把任一本当唯一口径。
   （若将来要"抽税同时缩池"，须单独裁摊派规则，不在本批。）
4. **国库落点**：`GoodsAccountKey(ActorRef(UNIT, unitId), location=单位当刻有效位置)`；单位无位置 ⇒ 具名拒。
   库存到格（`GoodsAccount` 的既定语义）：**单位移动不搬迁库存**，搬迁是另一次行动。
5. **人力口径（本批）**：只抽 `Sex.MALE` 且当前 tick 现算年龄落在**仓内既有成年档**
   （`AgeBracket.of(group.ageDaysAt(tick)) == AgeBracket.ADULT`，即 15–59 岁；年龄档的唯一拼写点在 `AgeBracket`，本工具不另写阈值）
   的 `social` 批次；不足 ⇒ **整条拒**（不部分、不拆别的批次）。未成年/老年/女性不动。征兵合法性/民怨后置。
6. **数量不足 ⇒ 整条具名拒**（与 `ClassFirstLevy` 的"不截断"同口径），不给部分抽取；
   家庭/批次之间的分摊用**瀑布**：可用量降序、同量按键规范串升序，逐值扣满为止（与 `ClassFirstActorWriteback` 的负增量分摊同法）。

### 6.1 一次性抽取 `simos.unit.levyRegion`（GM 组合工具）

- 形态：app 级 GM 工具（不是 CommandHandler——单条命令只能落一个命名空间），照 `WorldgenInitializeTool`/`AdjudicateTickTool`
  的先例**一条 `core.submitBatch` = 一条 revision**：
  - 先落新原语 `actor.AdjustAccounts`（净增量账，见 6.2）；
  - 再落既有 `social.SeedGroups`（整组覆盖，抽人力）；
  - 再落一条 `sd.PutInfo`（行动记录：unit/region/三项数量/来源计数，人可读）；
- 载荷：`{unitId, regionId, grain?, money?, manpower?, reason, branch?, expectedRevision}`；
  三项各自受对应上限约束；单位/区域/管辖校验照阶段 5 的判据。
- 粮/钱来源：region 各 hex 上 `ActorKind.HOUSEHOLD` 的 actor 账（`balances` 的 `grain`、`money` 的 `silver`）；
  可用量 = 余额 − 冻结额（夹 ≥0）；国库目标 = 上面第 4 条。
- 记录：`actor.AdjustAccounts` 的 entries 与 `social.SeedGroups` 的 entries **逐来源键**（owner+hex / 批次 id）；
  `sd.PutInfo` 一条人可读行动记录。**不另造第二份账**。

### 6.2 新原语 `actor.AdjustAccounts`（actor 域窄写）

- 载荷 `{entries:[{owner:{kind,id}, q, r, goods:{<commodity>:delta}, money:{<currency>:delta}}...]}`；
- 有符号增量、整条原子（任一违例 ⇒ 全拒）：任何余额结果 < 0 或侵占冻结额 ⇒ 具名拒；
  缺账 + 有负增量 ⇒ 拒；缺账 + 纯增 ⇒ 新建（其余表空）；同键在一条载荷里重复 ⇒ 拒；
- 配套 GM 窄工具 + `Shell` 注册 + `CatalogTool.PAYLOAD_HINTS`。

### 6.3 长期税（第二段，6.2 之后）

- **载体（架构硬约束）**：并入 `ClassFirstPopulationEconomyTimeParticipant` 的日循环——它已是 actor 写面的**唯一**参与者。
  ★ 不能另起 participant：`TimeProposalResolver` 对两个参与者的**同名模块变更 = module clash ⇒ 拒整次推进**
  （Core 手里的 ChangeSet 不透明、无法合并两份）；另起者若写 actor 会撞 population，若写 sd 会撞 `SdTimeParticipant`。
- **时点**：每一天 `ClassFirstSettlement` + actor 写回**之后**、月度人口学之前；税基 = 该日结算后的 actor 家户账。
- **行政能力计入效率**：`assessed = floor(balance × rate‰)`；`attainable = floor(assessed × administrationPerMille‰)`；
  `collected = min(attainable, available=余额−冻结)`；`administrationPerMille = 0` ⇒ 完全不征（硬门）。
- **缺口**：`collected < assessed` ⇒ 拆记 `adminShortfall = assessed − attainable` 与 `stockShortfall = attainable − collected`，
  逐户/逐维累计；本轮**只进日志（推进结束一条 INFO）+ actor 变更集**，不落 sd INFO
  （见上条：sd 写面由 `SdTimeParticipant` 独占；要落 INFO 须另裁一条通道，后置）。
- **重叠管辖**：按 `unitId` 升序依次征，后者见前者税后余额；本批不禁止重叠，按序可复现。
- **已知耦合（具名，不静默）**：税会抽走家户账余额，减少 `ClassFirstActorWriteback` 负增量分摊的可用池；
  若某户不足，次日写回 fail-closed 当场抛 = "政策超出账本承受力"的响亮失败。测试阶段须专门构造高压税用例。
- **读面**：participant 新增**只读** unit/map 切片（读管辖、区域 hex、单位位置）；写面仍只有 actor（外加原有的 economy/social）。
- 实现分层：App 侧一个**纯函数** `JurisdictionDailyTax.collect(actor, units, map, tick) -> (新 actor, Report)`，
  participant 日循环里调它并累计 Report；工具面不可达（自动结算，不是命令）。

- 非目标：完整税制/财政预算/救济（后续）；军队长期税由 `administrationPerMille=0` 的硬门堵住（用户 2026-09-30 前文的口径）。

## 4. 阶段 7：地方债

- 发行主体 = 单位国库（`ActorRef(UNIT, unitId)`）；债权人 = `classFirst.lenders` 或指定阶层池；
- 债务走 `classfirst` 的双边 `accounts`（owner = unit id），或 actor 侧新债权类型——**实施前单独裁一次"债务记在哪本账"**；
- 还款/催收必须有独立 participant；先做"发债 + 到期还款命令"，催收后置。

## 5. 阶段 8：组军（从地方抽人力 + 抽经济，来源/记录优先）

- 一条 app 级窄命令 `unit.RaiseUnit`：
  - 输入：`{unitId/新单位 id, name, regionId, manpower, grain?, money?, tools?, equipment?}`；
  - 来源：`social` 批次（按 region 的 hex 人口）+ `actor` 家户账（粮/钱/工具）——数量不足 ⇒ 具名拒，**不拆分不静默**；
  - 产出：新 `Unit`（member/equipment/speed/... 由载荷与来源量决定）+ 国库/来源账的原子扣减；
  - **来源可追溯**：命令载荷 + 变更集逐来源键；**行动记录**：一条 `Info`/事件（`unit.RaiseUnit` 的 outcome 里带来源清单）。
- 先做"来源与原子扣减"；"征兵合法性/民怨"等后果模型后置。

## 6. 顺序与纪律

1. 阶段 5（Unit + Jurisdiction + 两条命令）→ 2. 阶段 6（税/抽取）→ 3. 阶段 7（地方债，先裁账本）→ 4. 阶段 8（组军）。
- 每阶段一个写代码代理、只编译、不写测试（AGENTS.md §一.5/§三.0）；控制方审后提交推送；
- 破坏性模型改动（`Unit` 加组件）必须带 **兼容构造器 + codec 往返**；旧档缺键 ⇒ 空管辖。
