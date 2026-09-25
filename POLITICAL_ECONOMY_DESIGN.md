# Hex 级政治经济学生产模拟：代码与机制设计

> ## ★★ 取代声明（2026-09-25，追加不改原文）
> 用户交付了新的政治经济学资料（**聚合式：产业 → 阶层**，周期结算，复杂度不随人口线性增长）。
> 本文件的**逐主体**部分已被取代，新口径见
> [`docs/superpowers/specs/2026-09-25-aggregate-economy-redesign.md`](docs/superpowers/specs/2026-09-25-aggregate-economy-redesign.md)：
> - 被取代：`PeopleLot` 人口批次、`property` 逐块资产/权利、`production` 逐生产单位、`market` 订单簿、
>   `ledger` 逐主体账户、收益索取队列（→ 改为**制度分配函数**）。
> - 被保留：**日 tick（1 tick = 1 天）与单日步长**（§3）、存档 `time_base` 门禁、**多切片原子提案**
>   （`WorldTimeProposal`，§9 的协议面）、`economyMeta` 式"未激活"语义。
> - 已落地但仍有效的提交：`9639af1`（日制底座）、`6dc0df2`（多模块提案）、`40f60a9`（economy-api 骨架，
>   ID 集合将按新模型裁剪）、`a648a79`（ledger 切片，**待裁 D1**：退役或改为"存量+债务"所有者）。


**日期**：2026-09-24  
**状态**：待实现的设计稿；本文区分“现有代码”与“拟新增机制”  
**范围**：人口、生产资料、单位生产、商品与货币、索取权、市场、政府财政，以及它们和地图、国家、军队、时间线的接缝。

## 1. 结论与架构裁决

**模块边界已改为多切片方案**：`simos-social` 只拥有人口与自然需求；Hex 经济拆成 `simos-property`（生产资料及权利）、`simos-production`（单位生产及索取顺序）、`simos-ledger`（库存、货币、债务与转移账）、`simos-market`（订单、成交与运输）；**`simos-government` 独立拥有政府组织、辖区、执行能力、税制及支出规则**。`simos-economy-api` 只放共用 ID 和跨模块经济事件/转移意图。地图只给地形、道路和距离；SDSimos 保存国家身份与决策，UnitSimos 保存军事编制。`CoreSimos` 只调度和持久化，不放经济公式。**“单位生产”归 `simos-production`，不复用军事 `Unit`。**

**目标时间语义：全局 1 tick = 1 天。** 人口、生产、需求、交易、政府、军队移动和 SD 触发器共享这个日刻度。当前 [M3 设计](docs/superpowers/specs/2026-09-17-social-unit-simos-design.md) 的“1 tick = 1 小时”是待替换的旧设计，不构成本文件的约束。变更须同时修改相应源码、测试、场景参数和存档格式；第 3 节列出全部迁移落点。

当前 `TimeAdvance` 允许一次跨多个 tick，各参与者在同一轮只看**相同的基态**（[TimeAdvance.java](simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java)、[TimeParticipant.java](simos-util/src/main/java/io/mosire/simos/util/spi/TimeParticipant.java)）。目标实现把一次 `AdvanceTime` 收紧为**恰好前进一天**。`simos-app` 中的 `EconomyDayCoordinator` 在内存工作态里依次调用各领域模块的纯计算服务，形成一个包含多个模块变更集的提案；Core 对它原子提交一条 revision。协调器只规定步骤与传递结果，不拥有产权、市场或税收公式。推进前已提交的税制、产权和生产合同在本日生效；本次推进中由 SD 产生的决定从次日生效。

### 1.1 不可破坏的仓库约束

| 约束 | 代码依据 | 对本方案的含义 |
|---|---|---|
| 修改统一经过命令、变更集、修订 | [AGENT.md](AGENT.md)、[CommandBus.java](simos-core/src/main/java/io/mosire/simos/core/command/CommandBus.java) | 交易、税、征募、灾害均不得直接修改运行中的对象或另写一份数据库真相。 |
| 模块只拥有自己的数据 | [SimulationState.java](simos-util/src/main/java/io/mosire/simos/util/state/SimulationState.java)、[pom.xml](simos-social/pom.xml) | 每个新模块有自己的 namespace、Snapshot、ChangeSet、Codec；政府的税权不塞进人口或市场状态，国库余额不在政府和账本两处各存一份。跨模块验证与命令编排在 app。 |
| Core 不认识领域类型 | [CoreSimos.java](simos-core/src/main/java/io/mosire/simos/core/CoreSimos.java) | 新增通用的多模块时间提案协议与验证；Core 仍只认识 namespace、Snapshot、ChangeSet、Codec，不 import 经济模块。 |
| 状态组件与变更集逐项对应 | [SocialData.java](simos-social/src/main/java/io/mosire/simos/social/SocialData.java)、[SocialChangeSet.java](simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java) | Social 仅扩人口组件；其他模块也各自维护完整状态、对应的变更集、codec 和往返测试。 |
| 稳定 ID 是身份，地址用于定位 | [SocialResolver.java](simos-social/src/main/java/io/mosire/simos/social/resolve/SocialResolver.java) | 人口批次、生产单元、产权、债务、交易、政府都有稳定 ID；改名/迁址不改 ID。 |

### 1.2 已有能力与实际缺口

| 现有代码 | 已有事实 | 缺口或注意事项 |
|---|---|---|
| [SocialData.java](simos-social/src/main/java/io/mosire/simos/social/SocialData.java)、[SocialCity.java](simos-social/src/main/java/io/mosire/simos/social/city/SocialCity.java) | Hex 农村人口时态序列和城市节点人口 | 无年龄、性别、劳动、库存、资产、生产和财政；农村人口加同格城市人口才是该格总人口。 |
| [PopulationSeries.java](simos-social/src/main/java/io/mosire/simos/social/population/PopulationSeries.java) | anchor、增长率分段、离散事件，旧设计按小时解释 tick | 日制新世界只能用日参数；激活人口批次后，不能同时作为每日出生死亡的第二套权威人口账。 |
| [SettlementGenerator.java](simos-social/src/main/java/io/mosire/simos/social/gen/SettlementGenerator.java)、[SurplusEstimate.java](simos-social/src/main/java/io/mosire/simos/social/gen/SurplusEstimate.java) | 根据地形和潜在农业剩余生成村镇、城市 | “剩余”是生成城市用的估计指标，不是粮食库存、当日产出或税源。 |
| [GameMap.java](simos-map/src/main/java/io/mosire/simos/map/GameMap.java)、[TerrainType.java](simos-map/src/main/java/io/mosire/simos/map/terrain/TerrainType.java) | 地形、区域、道路及 food 等地形参数 | 地形参数是生产潜力输入，不是已被占有的土地或自然生成的商品。区域允许重叠，不等于独占行政辖区。 |
| [Nation.java](simos-sd/src/main/java/io/mosire/simos/sd/model/Nation.java) | 国家 ID、名称、homeRegion、`adminBudgetPerTick` | `adminBudgetPerTick` 是现有行政动作参数，不是国库余额；税权、税则、人员、财政另建。 |
| [Unit.java](simos-unit/src/main/java/io/mosire/simos/unit/Unit.java)、[Army.java](simos-sd/src/main/java/io/mosire/simos/sd/model/Army.java) | 军事编制、人数和国家归属 | 现有兵数没有从社会劳动力中扣除；编制根节点人数和子节点人数不能简单相加。 |
| [Shell.java](simos-app/src/main/java/io/mosire/simos/app/Shell.java) | 注册 map/social/unit/sd 四个 codec；时间参与者是 unit 与 sd | 新增五个有状态模块的 codec/handler/resolver，以及一个 app 级日协调器；现有社会仅三条写命令。 |
| [SqliteStore.java](simos-core/src/main/java/io/mosire/simos/core/store/SqliteStore.java)、[Replay.java](simos-core/src/main/java/io/mosire/simos/core/store/Replay.java) | revisions/events 与 JSON checkpoint 重放 | 商品与货币流水进入 `ledger` 快照/变更集，产权/生产/政府事实进入各自切片；不能把仅用于观察的日志当权威账本。 |

## 2. 权威对象与存储形状

不保存“封建程度”“工业化程度”“阶级百分比”或“剥削分数”。每种事实只有一个写入所有者；下面的 namespace 都是拟新增目标，**当前代码尚不存在**。`simos-economy-api` 仅定义稳定 ID、`ActorRef`、`CommodityId`、`EconomicEvent`、`TransferIntent` 和只读输入/输出契约，**没有 Snapshot、数据库或经济公式**。

| 模块 / namespace | 独占的权威状态与规则 | 不归它保存的东西 |
|---|---|---|
| 现有 `simos-social` / `social` | `PeopleLot`（人数、年龄、性别、居住、劳动系数、服役）、自然需求与未满足需求、出生死亡迁移；城市节点仍在这里 | 土地权属、生产周期、商品和钱、债务、交易、政府税权 |
| 新 `simos-property` / `property` | `AssetLot`（土地、牛、农具、机器等耐久生产资料）、所有权份额、控制/租用/抵押权、排他使用预留 | 商品库存、地租现金收入、生产进度 |
| 新 `simos-production` / `production` | 版本化 `ProductionRecipe`、`ProductionUnit`、劳动合同与 `ProductionCycle`、投入完成度、产品产权规则、**收益索取队列模板** | 真正持有的粮/布/钱、贷款余额、政府税率 |
| 新 `simos-ledger` / `ledger` | 各主体的商品与货币账户、双边转移凭据、贷款/欠薪/欠税等应收应付及清偿、经济激活标记 | 土地法定产权、订单价格、税法本身 |
| 新 `simos-market` / `market` | 市场节点、买卖订单、撮合结果、参考价格、运输和在途货物的**路线/履约状态** | 买卖双方的实际库存和货币余额；这些始终以 ledger 为准 |
| **新 `simos-government` / `government`** | 政府机构、关联国家 ID、名义辖区、税权、税则、官吏/执行覆盖、税额核定、预算与支出决定 | 国库商品/货币余额；国库是 ledger 中由政府持有的账户 |

`simos-economy-api` 只依赖 `simos-util` 与需要的地图 ID 类型；social 和五个新领域模块可依赖 api/util/map，但**彼此不作源码依赖**，SDSimos 与它们也不互相依赖。生产模块通过 API 的 `AssetRef`/`AccountRef` 引用生产资料和库存，政府模块通过稳定国家引用关联 SD；跨模块存在性和授权由 app 组合根验证。这个依赖方向既能独立测试每个模块，也让 `simos-property` 或 `simos-market` 将来继续拆分时不形成环。根 [pom.xml](pom.xml) 和各模块 POM 应按这张依赖图增模块，并以 Maven Enforcer 禁止反向依赖。

每个有状态模块各有 `*Data`、`*Snapshot`、`*ChangeSet`、`*Codec`、Resolver、写命令和逐组件往返测试。新存档的 `SimulationState.modules` 应同时包含 `social/property/production/ledger/market/government`，缺少已启用的任一切片即拒绝日推进；模块的 `ChangeSet` 只改自己的切片，Core 用一份 `WorldChangeSet` 原子保存六个结果。ledger 的 `economyMeta` 记录地图 ID、激活日、最后关账日、规则版本和迁移来源；未激活时其他新切片可以是空表，不能把空表误认成已经有经济。

`SocialData.populations` 与 `cities` 保留为**旧档/建城输入**。激活经济前，原查询行为保持；激活经济后，人口查询和 `PopulationFacet` 从 `social.people` 按 Hex、城乡求和，原序列与 `SocialCity.population` 仅作注明时刻的迁移来源。原 `social.SetPopulation` 和 `social.UpdateCity` 的人口字段在激活世界上要改为有明确年龄/性别和原因的调账命令，或拒绝模糊人口覆写；不能一边改旧序列一边让人口批次不变。城市的名称、坐标、区域仍由现有 `SocialCity` 保存。

### 2.1 人口与账户的粒度

`PeopleLot(count, ageDays, sex, residence, laborCoefficient, accountId, ...)` 代表 `count` 个属性与权利完全相同的**人**。每次产权、职业、健康或迁移仅作用其中一部分时，先把 lot 拆成两个稳定 ID，并记录 `splitFrom`；属性一致时允许合并并记录来源。年龄用天数或出生 tick，不用 5 年桶作运行中的时间单位；0—4、5—9 等只是查询聚合。首版不建家庭：生育、抚养、继承合同以后加入，不妨碍劳动和消费计算。

有效劳动按个人计算后求和：`availableLaborMilli = Σ(count × ageHealthSkillCoefficientMilli) − 已服役劳动力 − 已承诺的当日劳动`。计算过程允许“半日劳动”等千分劳动单位，人口数量始终是整数。未成年人、老人和病人的系数可为零或较小值；疾病对劳动和死亡的二次作用另接生活/营养模块。

## 3. 全局日 tick、迁移与精度

在新格式里 `SimosTimestamp.tick` 的单位**就是天**：创世状态是日 0，一次 `AdvanceTime(from,to)` 必须满足 `from == base.meta.timestamp.tick` 且 `to == from + 1`，这一次提交结算 `[from,to)` 的全部经济活动并使世界来到日 `to`。当前 [TimeAdvance.java](simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java) 只校验有上界，须补连续性和单日步长校验；[TimeRange.java](simos-util/src/main/java/io/mosire/simos/util/time/TimeRange.java) 的宽区间仍可作只读查询，但不能被写入式 `AdvanceTime` 当作一次多日结算。GUI/MCP 的“前进 N 天”连续提交 N 次，每次用上一次提交返回的新 `expectedRevision`；中途失败便停在已经提交的那一天，不跳过日结。这样**每天一 tick、每天一次完整结算、每天一条推进 revision**。

全局改日制须逐项更新：

| 旧小时语义 | 日制目标与改动点 |
|---|---|
| [SimosTimestamp.java](simos-util/src/main/java/io/mosire/simos/util/time/SimosTimestamp.java)、Core revision 的 `tick` | 数值保持 `long`，语义统一为日；新存档头和 SQLite 元数据必须带 `timeBase=DAY`、格式版本。缺标签的小时老档不可由新引擎直接写入。 |
| [UnitMoves.java](simos-unit/src/main/java/io/mosire/simos/unit/move/UnitMoves.java) 的 `speedAtDeparture × Δtick` | `Unit.speed` 与 `Movement.speedAtDeparture` 均改为 **MP/日**，距离成本仍为 MP；现有移动公式照常用天数差，重写字段说明、命令帮助、视图标签及军速夹具。旧小时速度迁移时按明确规则换算，例如 `dailySpeed = 24 × hourlySpeed`，溢出则拒绝导入，不能只改显示文字。 |
| [PopulationSeries.java](simos-social/src/main/java/io/mosire/simos/social/population/PopulationSeries.java) 的每 tick 增长率 | 新场景参数按**每日**给；活跃经济世界人口由 `PeopleLot` 的日出生/死亡/迁移账计算。旧小时序列导入时先在所选旧时刻求出人数，再生成日制人口批次，不能把旧 `growth` 原值当日增长率。 |
| [TriggerEvaluator.java](simos-sd/src/main/java/io/mosire/simos/sd/time/TriggerEvaluator.java) 的 `AfterTicks`、`AtOrAfterTick` 与 [DecisionMaker.java](simos-sd/src/main/java/io/mosire/simos/sd/model/DecisionMaker.java) 的 `decisionCadenceTicks` | 数字改为日数；新场景的决策周期、延期效果、战斗阶段按天校准。旧“持续 H 小时”如要迁移，按 `ceil(H/24)` 日并记录取整；绝不能让旧 `24` 从“一天”变成“24 天”。 |
| [Nation.java](simos-sd/src/main/java/io/mosire/simos/sd/model/Nation.java) 的 `adminBudgetPerTick` | 变为每日行政动作预算，非国库钱数；非零小时预算导入日制时按情景规则汇总为日预算，旧生成器设置的 0 仍为 0。财政余额始终由 `ledger` 中的政府账户保存。 |
| 测试世界、生成参数、GUI/MCP 文案及 [M3 设计](docs/superpowers/specs/2026-09-17-social-unit-simos-design.md) | 全部改以日解释；保留历史设计文件的旧裁决痕迹，在新设计/实现文档中标明本裁决取代其小时口径。所有“第 5 tick”测试要检验其对应的是第 5 **天**。 |

旧小时存档采用**离线、显式导入**：迁移工具只读旧 store 中用户选定的 revision/小时 `h`，用旧代码语义求当时人口和军事位置，连同地图、国家、装备等事实生成一个**新日制 store 的创世状态（日 0）**；原 SQLite 与 checkpoint 保留为只读历史档，不在同一条 revision 父链混用两种时间基。迁移器遍历所有内嵌时间值，不仅改顶层 `StateMeta`：人口 anchor/事件、单位位置和隶属时态段、在途行程、SD 效果/阶段/决策以及有时间戳的 Info 都须重锚到新日 0；历史上已完成的事件留在旧档，只有仍生效的合同/效果进入新世界。旧进行中的路线须从在 `h` 求得的位置/剩余路径重新立日制行程，旧预约触发时刻和决策周期按上表换算；无法无损换算的小时内事件要列入迁移报告并由场景规则明确取整。新 loader 对无 `timeBase` 的旧 store fail-closed，提示运行迁移工具，不能默默按天读。新创世文件与初始演示世界也须标日制版本。

数量统一用有单位的定点整数：人口 `long` 人、劳动千分日、土地千分亩、商品最小计量单位、货币最小币值、税率千分数。乘除用宽整数中间值并明确取整（不足最小单位的残差归属规则入 `EconomicRule`）；不能让 `double` 浮点误差决定钱、粮或人的产权。配方、税率、需求参数存版本化值；随机灾害若启用，用世界种子 + 当日 tick + Hex/生产单元 ID 派生固定随机数，分支重放可复现。

## 4. 生产资料、单位生产与产出

### 4.1 占有、控制、投入是三件事

土地从 `property` 切片的 `AssetLot` 与 `AssetRight` 查询；生产实际投入从 `production` 切片读取：

```text
owned(owner, day)       = 此日所有权份额 × 资产面积
controlled(actor, day)  = 有效经营/租佃/管理使用权 × 资产面积
invested(unit, day)     = 此生产周期确实预留且投入的资产面积
idle(hex, day)          = 可耕面积 − 当日各生产单位的排他投入面积
```

所有权能转移，控制权能通过租佃转移，生产投入必须另作预留；三者绝不互相推断。收益来源也据实际合同和结算计算。某种生产方式占土地 60% 由其生产单元实际投入面积汇总得到，不另存一个“推广度”。[HexCell.java](simos-map/src/main/java/io/mosire/simos/map/HexCell.java) 只存地形相关数据；地形 food 值可影响单位面积潜在产量，却不能替代土地账。

### 4.2 生产配方与瓶颈

`production` 的 `ProductionRecipe` 定义可用商品/资产、每单位规模需要的劳动、土地、畜力、原料及每阶段投入。开工规模以最短要素决定：

```text
scale = min(可控土地 / 每规模土地,
            已承诺劳动 / 每规模劳动,
            可用耕牛 / 每规模耕牛,
            已备种子 / 每规模种子, …)
```

例如 1 标准劳动最多经营 5 亩，10 劳动加 100 亩只能投入 50 亩；其余 50 亩列为未投入，不会凭空生粮。缺地的劳动力和缺工的土地所有者可通过租、雇、借、迁移建立合同；合同改变下一周期的 `rights`、劳动承诺和索取队列。

### 4.3 阶段、日投入与一次收获

`production` 的 `ProductionCycle` 记录 `startDay`、`durationDays`、阶段边界、当日应投、实投、累计满足度和产品产权。农业日历照常推进，某天缺工/缺种会压低对应阶段质量；不因为库存里还有地就自动补足。推荐首版产出式：

```text
dayFulfillment = min(1, 实交劳动/应交劳动, 实交原料/应交原料, …)
stageQuality   = 该阶段按日权重平均(dayFulfillment)
grossOutput    = floor(配方基准产量 × 实际规模 × 地形系数
                       × 各阶段质量函数 × 已记录的天气/灾害系数)
```

分母为零的要素不参加最小值。生产模块计算投入与产出意图，`ledger` 执行商品转移/消耗，`property` 执行工具折旧与耕牛状态变化；三个切片在同一日提案中一起提交。完成日前 `grossOutput=0`、不可交易或消费；到第 180 天才一次生成粮食。工业配方可设较短周期，但也有在制品、原料、折旧和产品产权。

## 5. 收益索取与两类结算

收获先进入 `ledger` 中单位生产的暂存账户，`production` 根据已签署的产权与索取模板计算分配意图，`government` 核定实物税，`ledger` 按顺序执行。默认同类索取优先序为：有效政府实物税 → 必要留种和再生产资料 → 到期固定债权 → 地租/资产收益 → 组织者约定额 → 劳动者固定或实物分成 → 剩余索取人。队列是生产合同的模板，不是所有制度的硬编码百分比；同优先级须有明示的按比例分摊与确定性取整规则。前文不同段落给出过“债权与地租”先后两种写法，实际顺序应落在**具体合同**，不能让实现含糊。拖欠形成 `ledger.Claim`，实收与应收分开。

小农中组织者、主要劳动者、资产占有者和剩余索取人可以是同一人口批次。佃农例：毛产 100 粮，政府实收 10，留种 15，约定地租 30，余 45 给劳动者；若毛产 60 且前三项仍可执行，余 5 给劳动者。谁先承担歉收风险从队列计算，不添加“压迫 +20”属性。要同时记应付税、政府实收和未收原因；行政覆盖不足时税法规定的 10 不会自动进国库。

资本主义单位生产采用**商品产权 + 货币债权**路径：产成品归组织者账户；工资在约定日从其货币账户支付，现金不足生成欠薪；产品出售后的货币收入再清偿到期税、欠薪、贷款等。工人不因为生产了 1000 匹布而自动拥有其中 40%。例：布销售收入 1300 银元，材料 500、折旧 100、工资 300、税 100，确认利润 300；另列**现金利润**与**应计利润**，避免原料早已购入或货未卖出时重复扣钱。工资规则可由生存需求、劳动力供求、谈判权、法定下限等决定，实际工资与剥削率由事后账算，不存永久“资本家/工人标签”。

## 6. 需求、市场、借贷与再分配

### 6.1 自然需求与有效需求

`social` 按每个 `PeopleLot` 的人数、年龄、生活条件和生产身份生成自然需求：生存品、社会再生产品、改善/奢侈品。`production` 生成生产投入需求，`government` 生成行政/公共购买需求；军队需求由 app 依据 `unit`/`sd` 事实接入。`ledger` 提供各主体库存、可支付货币及授信，`market` 据此形成有效买单。每一项记录 `required`、已有库存可满足量、当日购买后满足量、最终缺口；**自然缺口**不等于**有效买单**。无钱、无货、无信用的人可有 10 粮自然缺口而只有 0 有效需求。市场有粮仍可能有人饿。

消费分两次核算：日开始先从自有库存满足；不足部分形成购买/借贷/短工请求；成交和借粮后在当日关账前补足剩余生存需求，最后才记录未满足量。这样“先发现缺口，后买到粮”的人不会被误记为挨饿。首版缺口影响经济行为；长期缺口对身体、出生和死亡的函数可在后续生活模块接入，不在第一版暗加死亡率。

例如库存 300 粮、每日用 3、离收获 120 天，期间需要 360，现金流缺口 60；即使未来丰收，当前也必须出售劳动、购买、借粮、出售/抵押资产或迁移。预测缺口来自逐日产销预测，交易仍以实际现货和支付能力成交。

### 6.2 报价、成交和守恒

`market` 的订单包含买卖方 ID、市场/Hex、商品、数量、含税预算或卖方限价、有效日和信用额度；下单时用 `ledger` 的账户余额锁定可卖库存或可支付钱/已批准信用。按商品、市场、价格、时间、稳定 ID 排序确定性撮合；`market` 记录成交条件，`government` 计算交易税，`ledger` 记录未税价、买方实付、卖方实得、运输费、税和各收款方的实际转移。价格可由订单簿供需决定，并受库存、跨 Hex 路线、运力、税和市场规则影响；不能根据**没有支付能力的自然需求**自动抬出成交量。

首期可先实现同 Hex 现货市场；跨 Hex 扩展时，`market.Shipment` 保存线路、在途日、运费和履约状态，**在途商品的唯一产权与数量仍在 `ledger` 的在途账户**，损耗与过境税由账本和政府核定记录。在到货前不可被目的地消费。无论哪一期，市场交易不创造商品或货币：每一笔买方扣款 = 卖方入账 + 政府税入账 + 运输方收入，卖方出货 = 买方或在途账户入货 + 明示损耗。

`ledger` 的借款只把现有货币/粮从债权人移给债务人，同时创建本金债权；利息到期产生新应付款，不凭空制造现钞。以后若加入银行信用创造，必须另写货币发行与负债对账。违约可触发 `property` 中的抵押权执行、资产转让或 `social` 中的迁移，但各切片的变化必须在同一批命令/日提案中原子提交。

### 6.3 关系形成规则

一天结束仍有缺口的主体依次评估：出售富余商品 → 出卖未承诺劳动/找短工 → 租地或出租闲地 → 借贷/抵押 → 调整生产组织 → 迁移。选择依据是合同可行性、预期净收入和风险阈值，而非固定阶级配额。关系形成由 app 协调，分别写回 `social` 劳动/迁移、`property` 用益权、`production` 单位合同及 `ledger` 债务，在下一 tick 或下个生产周期生效；同一人不能同日把相同劳动卖给两家。

## 7. 政府：事件索取、执行覆盖与支出

`simos-government` 是单独模块、单独 `government` 状态切片。`FiscalGovernment` 以稳定 ID 关联 SDSimos 的国家 ID，不由 SD 直接保存财政数据。国家的 `homeRegion` 不是独占税界：地图 Region 可以重叠，[HexOwner.java](simos-app/src/main/java/io/mosire/simos/app/access/HexOwner.java) 也允许同格多国归属。政府状态应明确 `nominalHexes`/辖区引用、事件税权、争议地征税顺序或份额。对无法确定唯一合法收款者的事件拒绝结算并给出冲突，而非对同一粮重复全额征税。

每次收获、工资支付、销售、跨境、土地转让或继承发生时，取事件地点与日期适用的 `EconomicRule`。示例：10 银元未税价、20% 买方承担商品税，买方支付 12，卖方得 10，政府得 2；买方只有 10 则买不到完整一单位，成交量下降。土地国有化改变所有权，地租上限改变合同索取，最低工资改变新/续约合同，出口禁令拒绝相应运输订单；政策不直接给满意度或产量 Buff。

名义征税额与实收额分开。`government` 以定点因子计算某政府在 Hex 的执行覆盖：`staffCapacity × 路线可达 × 地方合作 × 治安`，各因子限制在 `[0,1000]` 千分数；人员、道路、距离和秩序是输入，不存一个可手调的“控制度”评价。`government` 保存税额核定与支出决定，`ledger` 保存政府账户的实际入库、欠税债权和支付流水。**未能查到的税基**与**已核定却未收到的欠税**分别记账，不能统称“政府收入”。官吏工资、军粮、道路/水利购买、赈济、上解从国库账户转给社会其他账户，因此财政是再分配而非资源销毁。

## 8. 阶级与统计：运行结果，不是初始化字段

`production` 的配方与合同定义可能出现的角色：自耕者、佃农、雇工、地主、工厂工人、组织者等。每个角色的**实际人数**由 app 查询层联读 `social.PeopleLot`、`property` 权利、`production` 劳动合同及 `ledger` 已结算收入后导出；一个人可以在统计期兼有多个角色。分类默认看完整生产周期或滚动一年，避免收获前一天把全部农民误判成“无收入”。

农业可计算 `ownLand / ownLaborNormalLandNeed`，并分析劳动收入、地租/资产收入、组织他人劳动所得的比重。土地明显不足者倾向贫农，基本匹配者倾向中农，土地较多且仍以自身经营劳动为主者倾向富农，主要依靠控制土地与组织他人劳动获得收入者归地主。阈值（例如比率区间、收入占比）是版本化**查询口径**，不是人的永久属性；临界者可标“混合/待定”。土地转卖、债务执行或雇佣关系改变后，下一统计窗口自然改类。工资额、利润、地租、剩余价值和剥削率同样从合同与实际账本计算并标明口径，不写回身份标签。

## 9. 每日演算的确定顺序

以下是 `simos-app/.../economy/EconomyDayCoordinator.simulate(base, range)` **拟实现**的语义。协调器拿完整基态，建立**仅在内存中的工作态**；逐步调用各模块的纯服务，将返回的状态和事件/转移意图交给下一步。每一步只能由所属模块计算和改变该模块的数据，协调器不自行计算税、价格、产量或账户余额。一次调用恰好结算一天。

```text
检查 from == 当前世界日 tick 且 to == from + 1；读取六个领域切片和 map 基态
1. government 取当日已生效政策；property 取使用权；production 取既有合同
2. social 更新年龄/可劳动量并提出自然需求；production 提出生产需求；ledger 核对现货与预算
3. ledger 先满足自有库存消费；property 锁定耐久资产；production 锁定劳动/原料并推进周期
4. production 对到期周期产生毛产出和索取事件；ledger 登记产出暂存
5. government 对收获/工资等事件核定税及可执行量；production 给出索取顺序；ledger 转移税、留种、债租、工资和剩余
6. market 从实际库存和可支付预算撮合订单；government 对交易/跨境事件核税；ledger 执行货币、商品和税的双边转移
7. social 用购买/借入商品补足当日生存需求并记录最终缺口；market/production/property/ledger/social 分别建立次日生效的交易、雇佣、租佃、贷款、迁移关系
8. government 决定可支付的行政、军粮、道路、赈济支出；ledger 执行；各模块分别验证本域不变量，app 验证跨域守恒
每个有变更的模块对自己的 base 与最终工作态求 ChangeSet；返回 WorldTimeProposal{namespace → ChangeSet, reads, writes}
```

政府会在步骤 5 与 6 **两次被调用**，ledger 会在多个步骤执行转移，但都只是在同一份内存工作态上推进；政府状态与账本状态分别只由自己的模块服务改写，最终各产出**一个**变更集。任何步骤失败则整日提案被拒，六个切片都不落盘。工资若合同规定“每日先付”，可在步骤 3 支付或产生欠薪；资本主义产出归组织者，步骤 5 不把货物平均分给工人。出生、死亡、营养作用首版可缺省为零，但数据结构和人口守恒检查保留入口。步骤 7 的新合同不能回头夺走步骤 3 已投的劳动/土地。

现有 `TimeProposal(namespace, changeSet, reads, writes)` 只能交一个命名空间，必须在 `simos-util` 新增通用 `WorldTimeProposal(participantId, moduleChanges, reads, writes)`（或等价契约），并让 [TimeAdvance.java](simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java) 对每个模块用其 codec 机械校验、合成已有 `WorldChangeSet`、在一个事务中写 revision 与事件。兼容期旧 `UnitTimeParticipant`、`SdTimeParticipant` 仍可返回单模块提案；它们和经济协调器都读取**同一外层基态**。经济协调器不写 `unit/sd`，故各自写集不重叠；其内部工作态才允许后一步读取前一步结果。Core 不需要知道经济步骤名称，也不规定政府应该何时征税。

多模块提案的读写集用实际触及的 canonical 地址，例如 `social:<mapId>:people.<id>`、`property:<mapId>:asset.<id>`、`production:<mapId>:unit.<id>`、`ledger:<mapId>:account.<id>`、`market:<mapId>:order.<id>`、`government:<mapId>:rule.<id>`。Core 当前只作**精确字符串**冲突匹配，读写相交仅记录、写写才拒绝（[TimeProposalResolver.java](simos-core/src/main/java/io/mosire/simos/core/advance/TimeProposalResolver.java)）；扩展后须对 `WorldTimeProposal` 中每个 namespace 一视同仁地做同样检查，且拒绝两个外层提案改同一模块/地址。内部多阶段改变同一账户不算外层提案冲突，以最终 `ledger` 变更集提交。

## 10. 代码落点与跨模块接线

| 改动点 | 拟新增/修改内容 | 依赖方向与注意 |
|---|---|---|
| 新 `simos-economy-api` | 六个领域切片共用的稳定 ID、`ActorRef`、`EconomicEvent`、`TransferIntent`、只读输入/输出契约 | 不含 Snapshot、领域状态、存储、税率或产量公式；各领域只依赖 api/util/map。 |
| [SocialData.java](simos-social/src/main/java/io/mosire/simos/social/SocialData.java)、[SocialChangeSet.java](simos-social/src/main/java/io/mosire/simos/social/change/SocialChangeSet.java)、[SocialCodec.java](simos-social/src/main/java/io/mosire/simos/social/codec/SocialCodec.java) | 只增人口批次、自然需求/缺口和服役相关组件；延续原人口/城市兼容入口 | 社会模块不实现资产、生产、账本、市场或政府对象。 |
| 新 `simos-property` | `PropertyData/Snapshot/ChangeSet/Codec`、土地和耐久资产、所有/控制/使用/抵押、资产预留服务与命令 | 只写 `property`，公开资产权利查询与使用/转让意图。 |
| 新 `simos-production` | `ProductionData/Snapshot/ChangeSet/Codec`、配方、单位生产、周期、合同与索取顺序、生产日服务与命令 | 只写 `production`，产出与投入由 ledger 执行实物账。 |
| 新 `simos-ledger` | `LedgerData/Snapshot/ChangeSet/Codec`、账户、库存、钱、债务、转移凭据、守恒校验与命令 | 只写 `ledger`；政府国库也是 ledger 账户。 |
| 新 `simos-market` | `MarketData/Snapshot/ChangeSet/Codec`、订单、确定性撮合、价格、运输服务与命令 | 只写 `market`；买卖支付由 ledger 执行。 |
| **新 `simos-government`** | `GovernmentData/Snapshot/ChangeSet/Codec`、政府机构、辖区、税制、执行能力、税额核定、支出服务与政策命令 | 只写 `government`；国家身份仍属 sd，国库余额仍属 ledger。 |
| `simos-app/.../economy/EconomyDayCoordinator.java`、[Shell.java](simos-app/src/main/java/io/mosire/simos/app/Shell.java) | 装配六模块纯服务、按第 9 节调度、注册 codec/handler/resolver/facet 和跨模块守卫 | app 唯一认识全部模块；协调器不放经济公式。codec/participant 个数从注册清单数，不写死。 |
| `simos-util/spi/WorldTimeProposal.java`、[TimeAdvance.java](simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java) | 通用多模块提案；验证每个模块的 codec、读写集、变更集与原子提交 | Core 不 import 任何经济类型；原单模块参与者可兼容。 |
| [TimeAdvance.java](simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java) | 验证 `range.from == base.timestamp` 且 `range.to == range.from + 1` | Core 只执行所有模块共用的一日步长规则；快进编排放在 app。 |
| `simos-app/.../query/*`、`gui/ApiViews`、GUI/MCP | Hex 经济概览、人口年龄/阶级派生、库存、周期、成交、税收、财政；同一权限/脱敏口径 | 军政机密、私人库存和债务依 actor 权限显示；未授权默认拒。 |
| [NationScope.java](simos-app/src/main/java/io/mosire/simos/app/access/NationScope.java)、`ArmyScope`、`ToolSupport` | 明确加入 `property/production/ledger/market/government` 的国家/军队/GM 读写范围 | 新 namespace 默认拒绝；不得因账户地址里出现国名就开放私人账。 |
| [WorldgenInitializeTool.java](simos-app/src/main/java/io/mosire/simos/app/tools/write/WorldgenInitializeTool.java)、[RichWorld.java](simos-app/src/main/java/io/mosire/simos/app/world/RichWorld.java) | 新创世建六个切片；缺切片的旧日制档与小时旧档均走显式离线导入 | 不把建城 `surplusPotential` 填成粮食；旧库不被首启种子覆盖。 |

政策写入使用 `government.*` 命令，SDSimos 的 `DirectiveWhitelist` 由已注册非 `sd.` 命令派生（[DirectiveWhitelist.java](simos-sd/src/main/java/io/mosire/simos/sd/spi/DirectiveWhitelist.java)）；新政策可经既有决定/裁决通道提出，再由 app 检查发令国家与政府 ID、辖区和执行权限。`MutationGuard` 负责跨 sd/map/六个经济切片的外键及权限检查；不能只靠 UI 隐藏按钮。新增查询须同时覆盖 GUI/API 与 MCP，沿用实际运行的权限服务，不在工具侧另造一个较宽的判定。

### 10.1 军事人口与国库的接缝

征兵采用 app 编排的**一批两条命令、一条 revision**：`social` 把指定人数/劳动量标为服役、创建 `ServiceAssignment`，`unit` 调整叶级编制人数。复员相反。`government` 提出军粮和军饷支出决定，`ledger` 从政府账户购买或拨付；军人作为人口仍有生存需求，但不再同时参加农业劳动。现有世界生成会给军队根单位和子单位都填人数（[WorldgenInitializeTool.java](simos-app/src/main/java/io/mosire/simos/app/tools/write/WorldgenInitializeTool.java)）；核对兵源时只算不重叠的叶级编制，不能把整棵树 `member` 相加。

战损先辨别 SD 战损记录和 Unit 真正扣员命令，按稳定战损/效果 ID 幂等地生成社会死亡/伤残命令，防止重复扣人。现有 [RecordCasualtiesHandler.java](simos-sd/src/main/java/io/mosire/simos/sd/spi/RecordCasualtiesHandler.java) 只记录损失，不能把它本身当人口已经减少。跨域后续效果若沿用 `SdCommandDrain`，必须使 GUI [GuiServer.java](simos-app/src/main/java/io/mosire/simos/app/gui/GuiServer.java) 与 MCP [AdvanceTool.java](simos-app/src/main/java/io/mosire/simos/app/tools/write/AdvanceTool.java) 的推进入口统一经过同一编排；当前二者直接 `core.submit`，而 [Shell.advanceAndDrain](simos-app/src/main/java/io/mosire/simos/app/Shell.java) 只有被显式调用才 drain。完成这个接缝前，不把“SD 战损自动影响社会人口”标成已实现。

## 11. 旧世界迁移与参数来源

**小时旧存档**先按第 3 节离线导入为新日制 store，不能仅靠 JSON 缺字段默认值就直接重放，因为它的时间数值代表另一种单位。**缺少新模块切片的旧日制存档**也需要显式 schema 导入：从选定 revision 读取当前状态，在新的日制 store 创世时补齐 `property/production/ledger/market/government` 的空快照，再保存来源坐标；不能在已有 revision 的 JSON 里偷偷补键。原因是 [TimeAdvance.java](simos-core/src/main/java/io/mosire/simos/core/advance/TimeAdvance.java) 对有变更模块要求基态有同名快照，[CheckpointEncoder.java](simos-core/src/main/java/io/mosire/simos/core/store/CheckpointEncoder.java) 也要求所有模块有 codec。**空切片不是已激活经济**：`ledger.economyMeta` 缺失时，日制世界可先沿用简化人口查询，但所有日期和参数仍按天解释。用 app 编排的一次原子初始化批次建人口批次、资产、生产/市场/政府初态、账户并写 `ledger` 激活标记；任何一步失败全部不落盘。日制格式内仅新增某个模块的字段时，才用旧 JSON 缺字段默认空、旧变更集默认 `Unchanged` 的兼容办法。

激活时从现有农村 `PopulationSeries.valueAt(currentTime)` 与各 `SocialCity.population` 提取人数，再按场景配置或带来源的确定性年龄/性别分布拆成 `social.PeopleLot`。四舍五入尾差按稳定 Hex/批次 ID 分派，逐 Hex 与全世界的人数严格守恒。土地面积及产权只进 `property`，已有商品库存/货币/债务只进 `ledger`，单位生产只进 `production`，市场与税制各进所属切片。它们**不能**从人口、地形 food 或城市 `localSurplus` 反推成事实；必须由场景参数/导入数据明确给出，并把“估计生成”标成来源，允许用零值起步。既有国家与军队若无可核对兵源，标记 `legacyUnlinkedService` 并要求显式对账，不能凭空指定某个农民已经入伍。

`config/worldgen/v17levant-nations.json` 的 `extraFacts` 有年粮、出口、作物、财政等叙事校准数字，可作为配方与初始资产的**参数来源**；[世界文档说明](docs/worlds/v17levant/README.md) 指出该批 Markdown 是离线资料，未自动进入运行态。需要写一条从“来源事实/估计 → 配方/土地/库存/税率”的可追溯映射，不能把资料里的年度量当日库存。由固定种子生成的场景须记种子和算法版本，确保重放/分支相同。

## 12. 验收样例与实现顺序

| 验收样例 | 必须观察到的结果 |
|---|---|
| 人口与劳动 | 1000 人但只有 500 标准劳动时，生产上限按 500 算；人口、劳动不是同一个字段。 |
| 要素瓶颈 | 10 劳动、100 亩、每劳动 5 亩时，当期最多投入 50 亩，其余列闲置。 |
| 农业周期 | 180 天配方在第 179 天无产粮，第 180 天一次收获；每天确已消耗的粮/种/劳动可核对。 |
| 现金流压力 | 库存 300、日耗 3、收获尚有 120 天，预测缺口 60；可借粮/短工，不提前获得未来产量。 |
| 顺序与歉收 | 100 粮扣 10 税、15 留种、30 地租后劳动者 45；毛产 60 时劳动者 5；各账户总和分别等于毛产。 |
| 资本生产 | 产成品先归组织者；工资 300 是货币债权；销售 1300、材料 500、折旧 100、工资 300、税 100 时确认利润 300。 |
| 无效购买力 | 贫农缺 10 粮但钱/货/信用均为 0，有自然需求 10、有效买单 0；市场即使有粮也不能自动成交。 |
| 商品税 | 未税价 10、买方税 20%：买方付 12、卖方得 10、政府得 2；预算 10 时整单位购买失败或仅买可分割量。 |
| 单日与快进 | 从日 0 到 1、再到 2 各提交一条推进 revision，各日各有完整账；单次从 0 到 2、重复/倒退的 `AdvanceTime` 均拒绝。GUI“快进 2 天”实际顺序发两次命令。 |
| 模块独占与原子性 | 政府只改 `government`，库存/国库只改 `ledger`，土地权利只改 `property`；故意让当日税收后的账本转移失败，六个模块状态与 revision 均保持推进前值。 |
| 守恒与回放 | 每日跨 `social/property/production/ledger/market/government` 按商品、货币、人口、土地逐项守恒；**每个模块**的 `apply(between(base,target),base)==target`；checkpoint 重放、分支重放与同种子重跑一致。 |
| 权限与政策 | 未授权国家不能改别国税制或看私账；地租上限/最低工资/禁运改变合同与成交；覆盖不足使税法应收与国库实收不同。 |

建议按四个可验收增量实施：

1. **全局日制与多模块提交底座**：完成第 3 节时间单位修改、`simos-economy-api`、通用多模块提案、六切片创世、旧档导入门禁；证明一次推进恰为一天且多切片原子提交。
2. **人口、产权与账本**：在 `social` 建 `PeopleLot`，在 `property` 建资产/权利，在 `ledger` 建账户/转移凭据，显式激活经济；先证明人数、产权与货币商品守恒。
3. **生产与基本税权**：实现 `production` 单位生产、日投入、周期和优先索取；同时建 `government` 最小税则与核定服务，由账本执行转移；用农业和工厂算例验收。
4. **市场、政府扩展与政军接线**：`market` 有效需求/交易，扩展 `government` 辖区执行、交易税和支出，接权限/决策通道、征募/战损去重、GUI/MCP 共用视图；再扩跨 Hex 运输和性能优化。

每个模块都要有自身变更集组件覆盖、codec 日制档往返、命令冲突/权限与一次日推进的集成测试；`SocialRoundTripTest` 只守社会组件，不能代替其他五个模块的测试。验收不能只看 Java 单元函数。单次多日推进被拒、快进按日提交、**跨切片原子性**、会计守恒、歉收优先级和无购买力饥饿是此机制的关键判据。项目的 Maven/前端门禁按 [AGENT.md](AGENT.md) 执行，避免并发 Maven 干扰测试结果。

## 13. 本文件的验证边界

本文件依据当前源码与既有设计文档编写，并未修改 Java、运行迁移或执行 Maven 测试。这里列出的新类型、命令、API 与公式都是**待实现规范**；表中“现有代码”仅表示已经核对到的接缝，不把历史计划文字当成运行事实。实现阶段需先将配方参数、收入分类阈值、征税冲突顺序、军人对账策略按具体世界校准，再以第 12 节的可执行样例关账。
