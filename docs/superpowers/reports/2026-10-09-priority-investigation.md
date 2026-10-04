# 2026-10-09 下一轮优先级调查（初稿，未定）

> **状态：只读调查 + 候选排序，不是计划、不是裁定。** 用户尚未确认优先级；本文件的作用是给下一次排序提供事实底稿。
> **方法**：读最新 handoff / `2026-10-02-open-bugs.md` / `2026-10-02-undeveloped-features.md` /
> `2026-10-02-mcp-admin-gaps.md` / 家户与人口架构 / 小世界铸币计划 / master/pre-modern 计划，并对以下代码点做只读抽查：
> `ShellMain`、`ShellConfig`、`EconomySeeder`、`EconomyData`、`ClassRow`、`Unit`、`Market`、
> `MarketSettlement`、`MerchantSettlement`、`MerchantFirm`、`HouseholdProfile`、`InfoEntry`、
> `log4j2.xml`、GM 工具清单。**本轮未跑 Maven、未改代码。**
> **最新原则**：`AGENTS.md §四.1`（2026-10-09 新增）：文档冲突时以日期最新且已落盘的文档为准；旧文档只追加“已被取代”，不回头改。

## 0. 用户已明确的口径（本轮调查的起点）

1. `production-runtime` 与 `production-runtime-government` 是**同一套体系**：市场不能缺少政府背书；
   `class-first` 退役；文档冲突以最新为准。
2. 家户边界：**经济模块的家户是生产参与的最小单位**；Social 模块的家户是人口账（谁由哪些批次组成、住哪、生死率）。
   `simos-social-api` 已从 Social 实现中分离，但它目前只放 ID / 视图 / 只读 SPI，不是经济/文化标签的存放处。
3. 经济、文化、军事都没搞全之前，不设计“跨界危机”的数据结构——挂起。
4. Log 必须加（不是可选）。
5. 启动参数：`--world=XXX` + 配置文件默认；**启动参数优先、配置文件做默认**；不加 `--demo`。
6. 先把后端功能模块做完，再把 Agent/MCP 工具（“另一种前端”）补上。
7. 价格 0 = **免费交易**：家户没钱存粮时，有需求者可从街上拿；购买只出运费；更大可能是买不到，因为运力不足。
   运力是跨格贸易的最大约束，其次是市场区。
8. 承运就是商人的运输货物职能；不做投机倒把，先把运输做出来。
9. 文化系统后置：先把家户社会和经济生产写明白。
10. 铸币：先用 GM 工具“意思意思”，等经济/家户稳定后再实验生产式铸币算法。
11. v17levant 地图重建后置：先家户和经济。
12. `Unit.manpower` 确定退役；装备照常发放。
13. 优先级未定；需要下一轮调查后再排。

## 1. 经济路线：class-first 退役的影响面

### 1.1 代码现状

- `EconomySeeder.FoundationProfile` 目前仍有三个 profile：`CLASS_FIRST` / `PRODUCTION_RUNTIME` /
  `PRODUCTION_RUNTIME_GOVERNMENT`（`simos-app/.../world/EconomySeeder.java:143-166`）。
- `PRODUCTION_RUNTIME_GOVERNMENT` = `PRODUCTION_RUNTIME` + 一个 GOV 家户 + 国库指向该家户 +
  `seignioragePerCycle` 试点（同文件 `:188-198`）。用户口径下的“同一套体系”与代码结构一致；
  当前问题不是两套结算引擎，而是**同一生产路径的政府维度被拆成了独立 profile 名字**。
- `WorldgenInitializeTool` 缺省仍传 `EconomySeeder.FoundationProfile.CLASS_FIRST`
  （`WorldgenInitializeTool.java:381` 附近、`:592`、`:623` 等）。
- `ShellMain` 当前**没有任何世界/profile 开关**：空库一律 `RichWorld.state(mapId)`
  （`ShellMain.java:26`、`:109`、`:157`），没有 `--world`、没有配置文件。

### 1.2 class-first 退役面（只读计数）

代码中仍引用 `classFirst/ClassFirst/CLASS_FIRST` 的主要位置：

- `EconomySeeder.java` 151 处（class-first 播种、GM 映射、读口）；
- `EconomyGmAdjustments.java` 81 处；
- `classfirst/ClassFirstState.java` 61 处；`ClassFirstPopulationEconomyTimeParticipant.java` 53 处；
- `ApiViews.java` 44 处；`EconomyData.java` 41 处（第 30 个组件 `classFirst`）；
- 另有 `ClassFirstPilotEngine`、`ClassFirstActorWriteback`、`ClassFirstSocialWriteback`、
  `ClassFirstPopulationWriteback`、`ClassFirstClassProjection`、`ClassFirstCommandGuard` 等十余个类；
- 24 个测试文件引用 class-first；前端 `panel-right.js` / `panels.js` 也读 class-first 读口。

⇒ **退役是一个可分批的独立批次**：先停止默认播种/读口分流，再删状态组件/命令/测试/前端分支；
不建议在“经济三件套”前先做大规模删除，但后续所有新功能都不应再依赖 class-first。

### 1.3 候选动作

- 裁定唯一对外 profile：`production-runtime-government` 是否改名、`PRODUCTION_RUNTIME` 是否保留为内部子形态；
- 默认播种切到政府背书的生产运行时；
- class-first 从“第二真理”降为历史兼容，随后单独批次删除。

## 2. 家户边界与可扩展通用结构

### 2.1 `simos-social-api` 的真实职责

`simos-social-api` 确实已分离（独立 Maven 模块），但它目前只放：

- 稳定 ID：`HouseholdId`、`PeopleLotId`；
- 位置/画像/人口形状：`HouseholdLocation`、`HouseholdProfile`、`HouseholdView`、`AgeBracketView`、
  `HouseholdVitalRate(s)`、`HouseholdPopulationEvent`、`Sex`；
- 只读 SPI：`HouseholdLookup`、`PopulationLookup`；
- 依赖仅 `simos-map` + `jackson-annotations`（`simos-social-api/pom.xml`）。

`Household` 本体与成员批次在 `simos-social`；
`Economy` 用同一个 `HouseholdId` 作 `ClassRow` 键（`ClassRow.id`），并另持经济事实：
人口、劳动、参与率、钱、债务引用、需求（`simos-economy/.../model/ClassRow.java:63-73`）。
⇒ 用户口径“经济家户 = 生产单位、Social 家户 = 人口”在代码里是**同一稳定 ID、两边各自持事实**，不是两套 ID。
上一轮草案写的“人口跨域唯一账本”不准确，撤回；准确说法是：**身份共享、事实按域分片**。

### 2.2 通用可扩展结构：可行，但要有边界

现有候选：

- `HouseholdProfile.metadata: Map<String,String>`（`simos-social-api/.../HouseholdProfile.java:26`）：
  自由字符串元数据，但属于 Social 的家户画像，适合“介绍/显示”，不适合经济/军事规则；
- `InfoSystem` / `InfoEntry`（`simos-util/.../info/`）：通用“key + 结构化值 + 有效期 + 来源”，
  但其类注明写：**凡影响领域计算的字段都不许走 Info**，否则退化成绕过领域模型的 JSON 垃圾桶
  （`InfoEntry.java` 类注）；⇒ 不能把文化/信用修正塞进 Info 当规则；
- `Actor` 只有 `ref` + `label`，明确禁止再挂库存/债务等状态（`Actor.java` 类注）。

因此建议的通用结构是：

1. **共享契约层只放身份/键**：`HouseholdId`（必要时加 `FacetKey`/`TagKey` 词表约定），不放状态；
2. **每个领域各持自己的家户分片表**，键 = `HouseholdId`：
   - Economy：家户经济事实（生产参与、需求、信用修正等）；
   - Culture/Religion（未来）：文化/宗教标签；
   - 基层组织（未来）：membership/effects；
   - Unit/Gov：家户编制/角色关系；
3. **效果必须是具名、可验证的类型**，不能是结算里随便读的 `Map<String,String>`；
4. 新增组件要同步 Commit/ChangeSet/Codec/反射往返守卫（铁律 5）。

结论：**“可扩展通用结构”本身可以做**，但应做成“共享 HouseholdId + 各域 facet 表 + 具名效果类型”，
而不是一张全项目共用的 JSON 标签袋。文化具体字段后置。

## 3. Unit/Army：manpower 退役与装备

### 3.1 代码事实

- `Unit` 已有第 5/6 组件：`List<CompositionEntry> manpower` 与 `List<CompositionEntry> equipment`
  （`Unit.java:84-102`）——这就是 D-006 的“任意人力类型 × 任意装备类型”数据形状；
  `2026-10-02-architecture-design-source.md` 里 D-006 的“现状：未实现”已经被代码追上，属旧文档。
- S3a 已加第 18 组件 `List<HouseholdId> households`（`Unit.java:102`），人口经
  `PopulationLookup.unitPopulation(unitId)` 现算（`ApiViews` 读口已接）。
- S3a 的**非目标**明确把 `Unit.manpower` 是否退役留给 S3b
  （`docs/superpowers/specs/2026-10-09-s3a-unit-household-containment.md` §1 非目标）。

### 3.2 用户口径下的目标

- `Unit.manpower` 作为**独立 headcount** 退役；
- `Unit.households` 是“这支部队/官府有哪些人”的稳定关系；
- 装备 `Unit.equipment` 照常作为编制/装备表发放、调整、战损；
- 待细化：兵种/军官等“角色”是否从家户/成员批次派生，还是需要另立角色关系表（可归后续 Unit 架构调查）。

## 4. 价格 0 = 免费交易：与现有代码的冲突点

### 4.1 现状

- `Market` 构造期明确拒绝 `price <= 0`，理由是“白送不是一种价格”
  （`Market.java:57-64`）；
- `Market.priceOf` 对**缺价**返回 0 = 本格不交易；`bidPriceOf` / `askPriceOf` 对 `price <= 0` 也返回 0
  （`Market.java:71、87-110`）；
- `MarketSettlement` 多处用 `reference <= 0 / bid <= 0 / ask <= 0` 当“不交易”：
  订单生成直接返回空表（`MarketSettlement.java:1186-1187`）、自适应价跳过（`:1049-1050`）、
  买家读数跳过（`:4207-4208`）等；
- 自适应价 `adaptiveNextPrice` 明确要求 `price > 0`（`:1089`），并 clamp 到 `MARKET_PRICE_FLOOR_MILLI = 1`
  （`:214、:1119-1121`）。

⇒ 允许 0 价不是改一个常量，而是要**区分“从未定价”与“明确 0 价”**，并逐条决定每个 `<=0` 分支的语义。

### 4.2 免费交易的关键副作用

- `freightOf(quantity, unitPrice, ratePerMille)` 与 `freightPerUnitOf(unitPrice, rate)` 都以
  **货物单价**为基数（`MarketSettlement.java:3689-3700`）；若成交单价为 0，运费也会变成 0，
  与“免费拿货但必须出运费”冲突。
- 因此 0 价语义落地时，需要同时决定：
  1. 免费商品的运费基数（路线成本 / 里程 / 重量 / 容量，而不是货值）；
  2. 订单生成里的“可负担量”不再被货价除零（`:4207+` 的 `budget * 1000 / reference`）；
  3. 运力不足成为主要 `unfilledReason`，而不是预算不足；
  4. 0 价与“无价不交易”的区分方式（例如 `Market.hasPrice(commodity)` 或专门的 free 状态）。

## 5. 承运 = 商人：现状与 merchantFee 0 的疑点

### 5.1 当前模型

- 商号本体 = `ProductionOrganization(mode=merchant)`；`MerchantFirm` 是挂在其旁的财务/运力状态，
  身份 = `organizationId`（`MerchantFirm.java` 类注）。
- 商号 principal = `ProductionOrganization.organizer`；创世时城市格的 principal 是该格城镇 landlord 家户 actor
  （`EconomySeeder.java:3685-3712、3848-3857`）。
- 市场跨区承运时 `CarrierPool` 选择 `CarrierChoice`，`MarketSettlement` 把 `CARRIER_FEE` 从买方铸给
  `choice.principalActor`（`MarketSettlement.java:3270-3290、3373-3395`）；
  `MerchantSettlement.feeRevenueOf` 只统计 `to == principalActor` 的 `CARRIER_FEE`
  （`MerchantSettlement.java:340-353`），周期末写回 `lastFeeEarnedMilli`。

⇒ 以“商号 = 商人”的口径，**当前商号账户就是 principal 家户 actor 的账户**，并不需要另造一个承运人实体；
用户之前批评的“没接到商号账上”更可能是：承运选择/跨区成交没有发生，或货款未实际落到 principal，
而不是缺一个独立账户字段。

### 5.2 `SevenHexFullChain3650Test` 的 0 运费疑点

- 该测试会构造多节点 `MarketTopology`（`SevenHexFullChain3650Test.java:2691-2705`），
  理论上存在跨区 lane；
- 但 `CarrierPool.candidatesFor` 受 `servesLane(firm, from, to)`、`homeHex`、`serviceRadiusHex`
  限制（`MerchantSettlement.java:258-320、542+`）；
- 跨区运费只在 `route != null` 的成交上产生（`MarketSettlement.java:3267-3284`）；
- 因此需要下一步只读诊断：该 3650 tick 里到底有没有跨区成交、候选商号有没有被 `servesLane` 排除、
  principal 有没有被当成买方的自承运而跳过。**不是先改“账户”字段。**

## 6. 全项目 Log：现状与补齐方式

本轮实测（`src/main/java`，`LoggerFactory` 或 `System.out` 命中文件数）：

- 有门面：`simos-economy`（`EconomyLog`）、`simos-social`（`SocialLog`）、`simos-unit`（`UnitLog`）；
- 零星：`simos-core` 4 个文件、`simos-app` 11 个文件；
- 零命中：`simos-util`、`simos-map`、`simos-calendar`、`simos-social-api`、`simos-sd`、
  `simos-actor-api`、`simos-actor`、`simos-economy-api`、`simos-gov`、`simos-army`。

`log4j2.xml` 当前只有 `simos.economy.*`、`simos.social.*`、`simos.unit.*` 三组开关
（`simos-app/src/main/resources/log4j2.xml:27-49`）。

`2026-10-04-economy-logging.md` §8 已写明用户裁定：经济调试收口后必须给其他模块按同一形态补
`XxxLog` + INFO/DEBUG/TRACE + `event=` 行。候选第一批：`map` / `sd` / `actor` / `gov` / `army` / `calendar` /
`core` / `app`；`*-api` 契约层通常只放接口或不动。

## 7. `--world` + 配置文件（用户 1.5 口径）

现状：

- `ShellMain.parse` 只认 `--store / --gui-port / --mcp-port / --approval-port / --bind-address /
  --opening-snapshot`（`ShellMain.java:74-98`）；
- `ShellConfig` 不可变，缺省值都在类常量里（`ShellConfig.java:52-65`）；
- `ShellMain.run` 在空库时硬编码 `RichWorld.state(config.mapId())`（`ShellMain.java:109、157`）；
- `RichWorld` 的资源固定在 `/worlds/v17levant.json`（`RichWorld.java:63`），没有世界 id → 生成器的注册表；
- `CorridorWorld` 已存在（小世界/走廊世界的另一个生成器先例），但未接入启动选择。

用户口径下的最小形态：

1. `--world=XXX`（是否兼容 `--world XXX` 由 parser 决定）；
2. 一个配置文件作为默认值源（world、store、端口等），**CLI 覆盖配置文件**；
3. 世界注册表：`worldId -> genesis provider`，`v17levant`、`small-world` 等各登记一条；
4. 非空库不覆盖（现有安全线不变）；
5. 不加 `--demo`。

## 8. 后端 vs Agent 工具（用户 1.6 口径）

把缺项按“是否只是工具包装”分类：

**后端已有，缺工具**（可后补，优先级低）：

- `simos.command.submitBatch`（`CommandBus.submitBatch` 已存在，批路径不写事件、批行身份只取首条命令）；
- `simos.social.cities`、`simos.sd.nations`、`simos.sd.armies` 等只读清单；
- `simos.map.nameHex`（可用 overlay Region 包装）。

**后端命令/状态缺失，不能只补工具**（优先级高）：

- `actor.MoveAccount` / `actor.TransferAccounts`（当前只有 `RemitGovTreasury` 且限资源/金额）；
- `social.MoveCity` / `DeleteCity` / `MovePopulationLots` + 批次读口；
- `map.MergeRegions` / `SplitRegion` / `ReassignHexes`（改完后的 jurisdiction/城市/税率/编制重算要同批）；
- `sd.DeleteNation`；
- `unit.SetVisionRadius`、`unit.TransferMembers` / `SplitStrength` / `MergeStrength`；
- 家户需求/产能、`mintScale`、GOV policy 等 `EconomyGmAdjust`/原生 state 写口；
- `simos.gov.moveCapital` 组合工具依赖以上多条后端命令。

**用户口径**：先做上面第二类（后端能力和数据模型），再补第一类（Agent/MCP 包装）和 GUI。

## 9. 已保存进度里对经济问题的既有处置

翻查结果：

- `HANDOFF-2026-10-09-social-api-s3a.md` §2 已列出三条既有经济红字
  （`AssetShareBook` 质押越界、`SevenHexFullChain` 的 `merchantFee=0`、`ExpectedProfit` 负值）；
- 同 handoff §3 已写死用户三条裁定（质押按比例跟份额、承运收入接商号、取消粮价最低价）；
- `2026-10-01-backend-mcp-stabilization-investigation.md` §12.12 / §13 已裁定：
  **经济真实长跑/数值校准本轮不做**，`GovDaily` 粮耗 120× 风险先记录，等国家初始化与 MCP 闭环稳定后单独排期；
  后来 `2026-10-01-frontend-f2-heatmap-design.md` 记录了 GovDaily 默认 120× 已修为每日 83 毫粮；
- `2026-09-30-class-first-economy-status.md` 的“class-first 唯一路径”已被 2026-10-06 之后的
  production-runtime 路线和 2026-10-09 用户“class-first 扫进垃圾堆”取代，按 `AGENTS.md §四.1` 视为历史留痕。

⇒ 经济长跑/数值校准**不在当前优先**；当前先处理的是正确性与数据流，不是把一年期读数一次性调到“真实”。

## 10. 候选优先级（未定，仅按依赖关系排）

### P0：经济/家户地基

1. 确认唯一经济路线：production-runtime-government 为下一阶段权威；class-first 停用/退役路线另批。
2. 经济正确性三件套：质押按比例跟份额 → 承运=商人运输/运费实收 → 0 价免费交易 + 运力约束。
3. S3b：`Unit.manpower` 退役、Economy/Unit/Gov 家户投影、`Unit.households` / `GovFormation.households` 自动同步。
4. 提交 `AGENTS.md §四.1`（已完成，commit `ab70b886`）；更新/取代与用户口径冲突的旧文档只需追加标注。

### P1：让它可运行、可操作

5. `--world` + 配置文件 + 世界注册表；空库 seed 选择；不加 `--demo`。
6. 后端行政/迁移命令（MoveAccount、MoveCity、MovePopulationLots、Region 合并/拆分、DeleteNation、
   TransferAccounts、SetVisionRadius 等）。
7. 全项目 Log 骨架：先 `map/sd/actor/gov/army/calendar/core/app`，按 `EconomyLog` 同形态。
8. 小世界 + 真实 DB/GUI 路线（可在经济/家户稳定后接入）。

### P2：等家户/经济稳定后再做

9. 文化/宗教/基层组织（含 1.2 的 facet 结构真正落地）。
10. 铸币生产式算法（先只保留/补最小 GM 工具）。
11. v17levant 地图重建验收（用户已明确后置）。
12. GUI 家户/GOV/铸币面板与地图信息面。

### P3：跨域/长期

13. 事件/通知/自动触发（用户已挂起）。
14. 数值校准、长跑 3650/720、性能 10k 格、确定性并行。
15. 外交/附庸/朝贡、攻城/城防、通用人物/家族、CI/发布/备份回滚。

## 11. 本轮调查仍未回答、需要下一步只读诊断的

1. `SevenHexFullChain3650Test` 的 `merchantFee=0` 到底是“无跨区成交”还是“有成交但 principal 未收款”；
2. production-runtime-government 当前在真实 store 上的启动/读口是否已完整，能否直接作为下一阶段默认；
3. `Unit.manpower` 退役后，兵种/军官角色是否走家户/成员关系，还是保留“只读投影”；
4. 0 价免费交易的运费基数（路线成本/里程/重量）以及运力耗尽的读数口径；
5. 通用 household facet 的契约名/模块归属（`social-api` 扩展还是新低层 api）；
6. `--world` 配置文件格式/路径与 worldId 注册表形态；
7. 后端命令清单里哪些已有现成 Core/领域原语、哪些是真正缺状态。

## 12. 2026-10-09 用户追加裁定（supersedes 本文件冲突处）

> 本节是新裁定，优先于本文件前面的候选排序与“退役另批”措辞。旧行不回头改，只在此追加取代。

1. **经济路线必须唯一，class-first 删干净。**
   - `production-runtime` 与 `production-runtime-government` 是同一套体系；**市场不可能没有政府背书**。
   - class-first 不是“留作第二轨/后置清理”，而是**目标状态里不存在**：状态组件、播种分支、命令、读口、
     前端分支、GM 工具、测试与旧文档引用一并列入删除批次。
   - 候选命名（待用户确认）：保留唯一公开名 `production-runtime`，政府家户/国库/铸币政策是它的**固定组成部分**；
     `production-runtime-government` 作为过渡名删除；`CLASS_FIRST` 线格式直接拒收。

2. **军官团 / 各级政府领导层也走家户。**
   - 军官团、领导层这种人少但配置特殊的主体，可以单独立**小家户**，再挂 Army/GOV 适用的特殊配置；
   - 基层军官也可以不单独立户，而是在一个大家户上挂**Army 维护的军官配置**；
   - 家户是人口/编制载体，Army/GOV 各自持有以 `HouseholdId` 为键的特殊配置分片（facet）；
   - `Info` 只放描述性/叙事性内容；若配置参与战斗、动员、供给等计算，必须落成 Army/GOV 的**具名状态类型**，
     不能拿 `InfoSystem` 当规则容器（其自身类注也禁止这样做）。

3. **运力与价格必须解耦。**
   - 现状 `TransportTariff` 是**从价费率**：`ratePerMille × 货款价值`；`MarketSettlement.freightOf` 的
     `unitPrice` 因子就是这么来的（探针旧模型，不是物理运输模型）。
   - 目标：运费按**运输工作量**算——至少是 `数量 × 单位运输成本`（单位成本由距离/地形/道路/运输方式决定），
     不再乘货价；价格只影响买方能不能付货款，0 价免费货仍必须付运费。
   - 运力是硬约束：`min(路线每窗口容量, 商号每周期运力)`；不足部分应记具名的运力不足/未收，而不是变成 0 运费。
   - 现状 0 价会让运费归零，正是本条要修的反例。

4. **本文件 §10 候选 P0.1 的“class-first 停用/退役另批”作废。** 新表述：
   `唯一 production-runtime（含政府） + class-first 彻底删除`，是本轮 P0 的第一件事；删除前仍按 AGENTS §一.8
   先写删除架构/边界文档，实现由子 Agent 一次做干净，测试后置。

## 13. 运费口径更正（2026-10-09 用户第二轮）

§12.3 的“单位成本由距离/地形/道路/运输方式决定”**作废**。用户口径：

> 运费只和商品种类有关，和商品价格无关。

⇒ 新表述（以本节为准）：

- **单位运费 = 该商品种类的属性**，不随成交价/市场价浮动；
- 价格只决定货款腿（0 价 ⇒ 货款 0），运费腿照收；
- 运力/市场区决定“能不能运、运多少”，这是通过运力占用与未成交原因表达，不是把运费乘上货价；
- 待确认一处：路线距离/道路/运输方式是否还修正**单位运费**，还是只影响**运力/时效/可达性**。
  报告暂按后者记录为候选口径，等用户确认后再写死。
