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
    long levyGrainCapPerCycle,                   // 一次性抽取：粮/周期
    long levyMoneyCapPerCycle,                   // 一次性抽取：钱/周期
    long levyManpowerCapPerCycle,                // 一次性抽取：人/周期（组军用）
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
| `unit.SetJurisdiction` | `{unitId, regions:[regionId...], levyGrainCapPerCycle?, levyMoneyCapPerCycle?, levyManpowerCapPerCycle?, administrationPerMille?}` | 整体替换管辖区域 + 缺省保持其余字段；`regions` 里每个 id 必须存在于当前 `GameMap.regions()`（不存在 ⇒ 具名拒，不静默丢）；空数组 = 撤销全部管辖 |
| `unit.SetTaxRate` | `{unitId, regionId, ratePerMille}` | upsert 某管辖区域的长期税率；`regionId` 不在该 unit 的管辖里 ⇒ 具名拒（先 `SetJurisdiction`） |

- 两条都走 `UnitChangeSet`/revision；配套 GM 窄工具 + `CatalogTool.PAYLOAD_HINTS` + `McpCoverageTest` 载荷断言（按既有工具面模板）。

### 2.3 验收（开发期）

spotless + `compile -pl simos-unit -am` + `compile -pl simos-app -am`；
测试代理输入：`Unit` 15 组件往返、旧档缺键 ⇒ 空管辖、Region 不存在拒绝、空管辖下 `SetTaxRate` 拒绝。

## 3. 阶段 6：区域税 / 一次性抽取（app 组合根，跨切片）

> 辖区在 unit、钱粮在 actor/classfirst、人口在 social ⇒ 只能由 **app 组合根**做原子命令。

- **长期税**（`administrationPerMille > 0` 才允许）：周期结算时，对每个管辖 Region 的 hex 上的家户 actor 账
  （`ActorKind.HOUSEHOLD`）按 `taxRatePerMilleByRegion` 扣粮/钱，转入该 unit 的国库 actor 账
  （`ActorRef(UNIT, unitId)`）；不足 ⇒ 记欠税/具名缺口，不静默跳过。
- **一次性抽取**（`economy.LevyRegion` 或 `unit.LevyRegion`）：GM 指定 `{unitId, regionId, grain?, money?, manpower?}`，
  受 `levy*CapPerCycle` 限制；manpower 从 social 批次扣、粮/钱从家户 actor 账扣，**同一 revision 原子**。
- 来源与记录：每条抽取在载荷/变更集里带 `regionId + 逐来源键（actor/批次 id）+ 数量`；行动记录用现有事件链 +
  命令载荷留痕，**不另造第二份账**。
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
