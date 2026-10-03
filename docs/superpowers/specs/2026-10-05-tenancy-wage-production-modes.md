# 佃农制与雇农制：两种农业生产方式（2026-10-05）

> 来源：用户 2026-10-05 对明清农村经济语义的裁定。
> 关联：`2026-10-04-market-debt-architecture.md`（市场/债务循环）、
> `2026-10-04-market-debt-implementation-plan.md`（M1–M6）、
> 既有模型 `ProductionMode` / `ProductionOrganization` / `ProductionRelation` / `CompensationRule`。
> **探针结果**：`docs/superpowers/reports/2026-10-05-tenancy-wage-cycle-results.md`
> （四条基准循环闭合；收成冲击下各 mode 的风险分配读数）。
> 结论：**能做，而且现有类型词汇已经覆盖六种租/工资变体；缺的是运行时（组织、结算、选择）与测试。**

## 0. 一句话

生产方式先按**谁组织生产、产出先归谁、风险谁承担**分成两大类：

- **佃农制（TENANCY）**：地主只出土地（+ 契约约定的资产）；佃农家户是经营者，产出先归佃农，
  再向地主交租（定额实物 / 分成 / 定额货币）；佃农承担经营与收成风险。
- **雇农制（WAGE_FARM）**：地主/经营地主是经营者，出土地、种子、农具、耕牛等；产出先归经营者，
  再给雇农发工资（实物 / 货币 / 混合 + 食宿）；经营者承担经营与收成风险，雇农只出卖劳动。

佃农**不是**“地主给工资的人”；雇农**不是**“向地主交租的人”。两种方式首先就是两条不同的生产关系。

## 1. 角色四分层（与既有术语对齐）

| 角色 | 含义 | 佃农制 | 雇农制 |
|---|---|---|---|
| AssetOwner | 生产资料所有者 | 地主（土地） | 地主/经营地主（土地+资本） |
| ProductionOperator | 谁组织生产、投入谁的资本、产出先归谁 | 佃农家户 | 地主/经营地主/管家 |
| LaborProvider | 谁实际出劳动 | 佃农家庭劳动（可外加少量短工） | 雇农（长工/短工） |
| InventoryOwner | 产出先入谁的账 | 佃农家户 | 经营者 |

这四层**不默认相同**。现有模型已经把它们分开表达：

- `ProductionOrganization.organizer` = ProductionOperator；
- `ProductionOrganization.outputOwnership` / `ProductionRelation.residualOwner` = InventoryOwner；
- `ProductionOrganization.laborSources` + `LaborAllocation` = LaborProvider；
- `ProductionOrganization.assetSources` + `AssetShare` = AssetOwner（资产份额推迟到真正需要时接入）。

## 2. 佃农制：三种租形，一个共同骨架

共同骨架：

```text
operator        = 佃农家户
inputSupplier   = 佃农家户（种、肥、农具、耕牛由佃农出；地主只出土地）
laborSource     = TENANT
residualOwner   = 佃农家户
outputOwnership = 佃农家户
```

三种租形只是 `ProductionRelation.rules` 里指向地主 cohort 的一条规则不同：

| 租形 | RuleType | Pool | Weight | 商品/币种 | 数量 | 地主风险 | 佃农风险 |
|---|---|---|---|---|---|---|---|
| 定额实物租 | `FIXED_IN_KIND_RENT` | `FIXED_AMOUNT` | `NONE` | 粮 | 固定 X 石/周期 | 几乎零（欠租另算） | 全部收成风险 |
| 分成租 | `OUTPUT_SHARE` | `GROSS_OUTPUT` 或 `NET_AFTER_INPUTS` | `NONE` | 粮 | `ratePerMille`（300/400/500‰） | 按比例分担 | 按比例分担 |
| 定额货币租 | `FIXED_MONEY_RENT` | `FIXED_AMOUNT` | `NONE` | 币种（银） | 固定 X 两/周期 | 几乎零（欠租另算） | 收成风险 + 粮价风险 |

要点：

- 分成租的 `pool` 选 `GROSS_OUTPUT` 还是 `NET_AFTER_INPUTS` 是一个**制度事实**：
  地主是否先扣种子/投入再分；两个都要显式写在规则里，不许在结算代码里猜。
- 定额实物租的量是**周期固定额**，与产出无关；产出不足时不够交的差额是**欠租**，
  应由 M3 落成佃农的单边债务，而不是从规则里静默消失。
- 定额货币租把“收成风险”之外又加了“粮价风险”：佃农必须把粮卖掉换钱。
  现金租的结算顺序必须是“**先市场卖粮 → 再交银租**”，或者“先记欠租、下一期卖粮还”，
  不能假设佃农账上天然有银。
- 佃农可以雇少量短工（家庭劳动不够时），但这不改变经营主体；本期先不做，
  在 `ProductionOrganization.laborSources` 里允许同时出现 `TENANT + WAGE` 两类来源即可。

## 3. 雇农制：三种报酬组合，一个共同体

共同骨架：

```text
operator        = 地主/经营地主（ESTATE / 经营家户）
inputSupplier   = operator（种子、农具、耕牛由经营者出）
laborSource     = WAGE
residualOwner   = operator
outputOwnership = operator
```

工资是**劳动者的报酬**，不是产出分成；报酬可以由多个 `CompensationRule` 叠加：

| 报酬组合 | 规则构成 | 典型期限 | 说明 |
|---|---|---|---|
| 货币工资 | `FIXED_MONEY_WAGE`（银/周期/人） | 短工/日工/月工 | 单位时间工资通常高于长工；价格风险归经营者 |
| 实物工资 | `FIXED_IN_KIND_PER_LABOR`（粮/千分劳动） | 长工 | 口粮直接来自产出；收成风险归经营者 |
| 混合工资 | `FIXED_MONEY_WAGE` + `FIXED_IN_KIND_PER_LABOR` | 长工为主 | 钱 + 粮；可再加食宿对应的实物规则 |

“食宿”的建模：

- ** meals **：一条 `FIXED_IN_KIND_PER_LABOR`（粮/食物商品）即可表达；它是工资的一部分，不是额外福利。
- **housing/clothing**：当前 `CommodityId` 里若没有“房/衣”商品，先用 `ClassPosition.ruleExtensions`
  或 `CompensationRule` 的扩展位记名字，等这些商品进入商品表后再落成规则；不提前造永远为空的字段。
- 长工与短工不是两种生产方式，而是同一 `WAGE_FARM` 下的**合同期限与报酬组合**：
  - 长工：年度/季度合同 + 粮 + 食宿 + 少量现金；
  - 短工：按日/按月 + 较高的单位工资 + 当日饭食。
  期限是合同状态（起止、支付节奏），不是 `CompensationRule` 的字段；M1 先落报酬组合，M3 再接合同期限。

## 4. 与现有类型的映射

| 语义 | 现有类型/字段 | 现状 |
|---|---|---|
| 生产方式身份 | `ProductionMode(id, name, version, classStructureId)` | 已有 E1 模型；尚无 tenancy/wage_farm 两个 mode 的落盘种子 |
| 阶层位置 | `ClassPosition(relationToMeans, laborRole, surplusRole)` | 已有；`landlord`/`landless_laborer` 位置已有，`tenant` 位置需按 tenancy 补 |
| 生产组织 | `ProductionOrganization(organizer, laborSources, assetSources, inputSources, outputOwnership, relationTemplateRef)` | 已有；需要 M1 用 tenancy/wage 的关系模板填充 |
| 生产关系 | `ProductionRelation(operator, inputSupplier, rules, residualOwner, laborSource)` | 已有；`inputSupplier` 正好承载“佃农出种” vs “经营者出种” |
| 补偿规则 | `CompensationRule(type, recipient, pool, weight, ratePerMille, fixedAmount, commodity, currency, priority)` | 已有六档 `RuleType`：`FIXED_IN_KIND_RENT` / `OUTPUT_SHARE` / `FIXED_MONEY_RENT` / `FIXED_IN_KIND_PER_LABOR` / `FIXED_MONEY_WAGE` / `SELF_RETENTION` |
| 劳动来源 | `LaborSource.TENANT` / `LaborSource.WAGE` | 已有 |
| 受方 | `Recipient.ToHousehold`（运行期主口径）/ `ToCohort`（旧档视图） | 已有；租/工资都应写给稳定 `HouseholdId`，不由视图推导 |
| 结算引擎 | `ProductionSettlement` 公式表 | 目标结算器尚未接入：当前主代码仍是 class-first 试点路径（世界级 4 池 + 硬编码 `tenancy_agriculture`）；M2/M3 建新结算器 |
| 生产决策 | `planEntries` / `ProductionCandidate` | 设计已存在（2026-09-28 plan §S2.3）；M1 复用 |

结论：**数据形状不需要大改**；需要的是把上述组合写成默认关系模板/显式 relation 数据，
并把结算/组织/选择运行时建起来。

## 5. 选择哪一种生产方式（不能只看“产出低”）

用户 2026-10-05 的直觉是“产出很低 → 佃农制”。更准确的分层：

| 条件 | 更可能出现 |
|---|---|
| 收成波动大、佃农缺抗风险能力 | 分成租（地主分担风险） |
| 佃农有资本、有市场、收成较稳 | 定额实物租 / 定额货币租 |
| 地主有资本、能监督、劳动力无地化 | 雇农制（工资劳动） |
| 地主不愿经营、只想收租 | 佃农制 |
| 粮价/市场不稳定 | 定额货币租风险高，佃农更倾向分成或实物租 |

所以生产方式选择应由 `ProductionCandidate` 评分决定，评分至少含：

1. 预期产出与方差（风险）；
2. 经营者可投入的资本（种子/农具/耕牛）；
3. 劳动者/佃农的保留收入（不参与时的生存替代）；
4. 监督/管理成本（雇农制更高）；
5. 市场可达性与粮价风险（货币租/货币工资更敏感）；
6. 地租/工资的刚性（定额租 > 分成 > 工资？实际上工资对劳动者最稳、地租对地主最稳）。

“产出低”只是方差/保留收入的一个输入，不是硬阈值。

## 6. 现金腿与债务（关键缺口）

现有规则类型里货币档已经“真的结算”，但当前口径是：**只从付款方本期可见货币支付；
付不出的部分只进读数、不落债权**。这不够支撑真实的租佃/雇工经济：

- 货币租佃农：收成后先卖粮才能交银租；粮卖不掉时是**欠租**。
- 雇农制经营者：青黄不接/收成前要垫工资；垫不出时是**欠薪**。
- 长工被雇主欠薪、佃农欠地主租，都是真实存在的债务关系。

建议（与市场/债务 spec 的“单边负债”一致）：

```text
租约/工资规则先结算可见货币部分；
不足部分记在付款方（佃农/经营者）的单边债务上，具名 reason：
  rent_arrears:<rentForm> / wage_arrears:<wageForm>
```

同时明确结算顺序：**市场 → 现金租/工资 → 计息 → 消费/死亡**，
让“卖粮交租/卖粮发工资”有路径；顺序本身写进 M2 的主循环。

## 7. 实施批次（并入 `2026-10-04-market-debt-implementation-plan.md` 的 M 计划）

| 本文件批次 | 对应 M 相位 | 内容 | 门禁 |
|---|---|---|---|
| N1 | M1 | `ProductionMode` 种子：`tenancy`（三种 rent form）、`wage_farm`（长/短/混合 wage form）；对应 `ClassStructure`/`ClassPosition`（tenant 位置、operator/landlord/laborer）；默认 `ProductionRelation` 模板 | 编译 |
| N2 | M1 | `ProductionCandidate` 选择：在 tenancy / wage_farm / self / household 之间按 §5 评分；`ProductionOrganization` 填充 organizer / inputSupplier / outputOwnership / laborSources | 编译 |
| N3 | M2 | 结算运行时：重建 `ProductionSettlement` 公式表；覆盖六档 `RuleType`、`Pool × Weight` 组合；现金腿 + 欠租/欠薪债务 | 编译 |
| N4 | M2/M4 | 市场接入：现金租/工资结算前先允许卖家卖粮；运输/价格接入市场区 | 编译 |
| N5 | M6 | 测试与长程验收：租形/工资形/风险分配/欠租欠薪/两种 mode 同世界对照 | 全绿 |

### 测试矩阵（N1–N5 收尾）

1. 定额实物租：1000 产出 / 400 租 → 佃农 600，地主 400；
2. 分成租：40:60 → 佃农 600，地主 400；
3. 定额货币租：佃农卖粮换银交租，低产时产生欠租债务；
4. 货币工资：雇农拿工资，经营者承担收成波动；
5. 实物/混合工资：长工口粮+货币，短工货币+饭食；
6. 两种 mode 对照：同一块地、同一产出，佃农与雇农的收入/风险不同；
7. 违约：佃农欠租、经营者欠薪都落单边债务，死亡/迁移按人口带走；
8. 选择：低产出高方差时倾向分成，市场稳定+有资本时倾向定额/货币/雇农。

